package com.actilazion.aries_transaction.security;

import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.domain.AccountStatus;
import com.actilazion.aries_transaction.account.domain.AccountType;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.config.JwtService;
import com.actilazion.aries_transaction.identity.application.AuthenticatedUserPrincipal;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.transaction.domain.TransferPreview;
import com.actilazion.aries_transaction.transaction.domain.TransferPreviewMode;
import com.actilazion.aries_transaction.transaction.infrastructure.TransferPreviewRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ExternalTransferSecurityIntegrationTest {
    private static final AtomicLong ACCOUNT_NUMBERS = new AtomicLong(710_000_000_000L);
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @LocalServerPort int port;
    @Autowired UserRepository userRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TransferPreviewRepository previewRepository;
    @Autowired JwtService jwtService;

    @Test
    void protectedWriteEndpoints_withoutTokenReturn401() throws Exception {
        assertThat(postWithoutAuthorization("/api/v1/transfers/preview", "{}" + System.lineSeparator()).statusCode())
                .isEqualTo(401);
        assertThat(postWithoutAuthorization("/api/v1/transfers", "{}").statusCode()).isEqualTo(401);
        assertThat(postWithoutAuthorization("/api/v1/accounts", "{}").statusCode()).isEqualTo(401);
    }

    @Test
    void userAndAdmin_cannotPreviewForeignSourceAccount() throws Exception {
        User owner = user(Role.USER);
        User caller = user(Role.USER);
        User admin = user(Role.ADMIN);
        Account foreignSource = account(owner, "5000.00");
        Account recipient = account(user(Role.USER), "0.00");
        String body = previewBody(foreignSource, recipient, "1000");

        HttpResponse<String> userResponse = post("/api/v1/transfers/preview", tokenFor(caller), body);
        HttpResponse<String> adminResponse = post("/api/v1/transfers/preview", tokenFor(admin), body);

        assertForbidden(userResponse);
        assertForbidden(adminResponse);
    }

    @Test
    void admin_cannotExecuteForeignPreview() throws Exception {
        User owner = user(Role.USER);
        User admin = user(Role.ADMIN);
        Account source = account(owner, "5000.00");
        Account recipient = account(user(Role.USER), "0.00");
        TransferPreview preview = preview(owner, source, recipient, OffsetDateTime.now().plusMinutes(5), null);

        HttpResponse<String> response = post("/api/v1/transfers", tokenFor(admin), executeBody(preview));

        assertForbidden(response);
    }

    @Test
    void preview_successMasksRecipientAndDoesNotLeakIdentifiersOrBalance() throws Exception {
        User owner = user(Role.USER);
        User recipientOwner = user(Role.USER);
        Account source = account(owner, "5000.00");
        Account recipient = account(recipientOwner, "987654.00");

        HttpResponse<String> response = post(
                "/api/v1/transfers/preview", tokenFor(owner), previewBody(source, recipient, "1000"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("********" + recipient.getAccountNumber().substring(8));
        assertThat(response.body()).doesNotContain(
                recipient.getAccountNumber(), recipient.getId().toString(), recipientOwner.getId().toString(), "987654.00");
    }

    @Test
    void preview_unknownRecipientDoesNotEchoAccountNumber() throws Exception {
        User owner = user(Role.USER);
        Account source = account(owner, "5000.00");
        String unknown = "999999999999";
        String body = """
                {"mode":"EXTERNAL","sourceAccountId":"%s","recipientAccountNumber":"%s","amount":"1000","currency":"VND"}
                """.formatted(source.getId(), unknown);

        HttpResponse<String> response = post("/api/v1/transfers/preview", tokenFor(owner), body);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("RECIPIENT_UNAVAILABLE").doesNotContain(unknown);
    }

    @Test
    void preview_businessInvalidAmountsReturnStable422() throws Exception {
        User owner = user(Role.USER);
        Account source = account(owner, "5000.00");
        Account recipient = account(user(Role.USER), "0.00");

        HttpResponse<String> belowMinimum = post(
                "/api/v1/transfers/preview", tokenFor(owner), previewBody(source, recipient, "999.99"));
        HttpResponse<String> excessiveScale = post(
                "/api/v1/transfers/preview", tokenFor(owner), previewBody(source, recipient, "1000.001"));

        assertInvalidAmount(belowMinimum);
        assertInvalidAmount(excessiveScale);
    }

    @Test
    void compatibilityTransfer_businessInvalidAmountReturnsStable422() throws Exception {
        User owner = user(Role.USER);
        Account source = account(owner, "5000.00");
        Account recipient = account(user(Role.USER), "0.00");
        String body = """
                {"fromAccountId":"%s","toAccountId":"%s","amount":999.99,"currency":"VND","idempotencyKey":"%s"}
                """.formatted(source.getId(), recipient.getId(), UUID.randomUUID());

        HttpResponse<String> response = post("/api/v1/transfers", tokenFor(owner), body);

        assertInvalidAmount(response);
    }

    @Test
    void expiredAndConsumedPreviewsReturnStable409() throws Exception {
        User owner = user(Role.USER);
        Account source = account(owner, "5000.00");
        Account recipient = account(user(Role.USER), "0.00");
        TransferPreview expired = preview(owner, source, recipient, OffsetDateTime.now().minusSeconds(1), null);
        TransferPreview consumed = preview(
                owner, source, recipient, OffsetDateTime.now().plusMinutes(5), OffsetDateTime.now());

        assertPreviewUnavailable(post("/api/v1/transfers", tokenFor(owner), executeBody(expired)));
        assertPreviewUnavailable(post("/api/v1/transfers", tokenFor(owner), executeBody(consumed)));
    }

    @Test
    void accountCreation_replaysOriginalSnapshotAndRejectsChangedRequest() throws Exception {
        User owner = user(Role.USER);
        String key = UUID.randomUUID().toString();
        String originalBody = accountBody("PERSONAL", "Original", key);

        HttpResponse<String> created = post("/api/v1/accounts", tokenFor(owner), originalBody);
        JsonNode originalData = objectMapper.readTree(created.body()).get("data");
        UUID accountId = UUID.fromString(originalData.get("id").asText());
        Account account = accountRepository.findById(accountId).orElseThrow();
        account.setBalance(new BigDecimal("5000.00"));
        account.setStatus(AccountStatus.FROZEN);
        accountRepository.saveAndFlush(account);

        HttpResponse<String> replay = post("/api/v1/accounts", tokenFor(owner), originalBody);
        HttpResponse<String> conflict = post(
                "/api/v1/accounts", tokenFor(owner), accountBody("BUSINESS", "Changed", key));

        assertThat(created.statusCode()).isEqualTo(200);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(replay.body()).get("data")).isEqualTo(originalData);
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(conflict.body()).contains("ACCOUNT_CREATION_IDEMPOTENCY_CONFLICT");
    }

    @Test
    void accountCreation_atFiveActiveAccountsReturnsStable409() throws Exception {
        User owner = user(Role.USER);
        for (int index = 0; index < 5; index++) {
            account(owner, "0.00");
        }

        HttpResponse<String> response = post(
                "/api/v1/accounts", tokenFor(owner), accountBody("PERSONAL", null, UUID.randomUUID().toString()));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("ACCOUNT_LIMIT_EXCEEDED");
    }

    private User user(Role role) {
        String suffix = UUID.randomUUID().toString();
        return userRepository.save(User.builder()
                .fullName("External Transfer Security User")
                .email("external-security-" + suffix + "@test.local")
                .passwordHash("not-used")
                .role(role)
                .isActive(true)
                .build());
    }

    private Account account(User owner, String balance) {
        return accountRepository.saveAndFlush(Account.builder()
                .user(owner)
                .accountNumber(String.format("%012d", ACCOUNT_NUMBERS.getAndIncrement()))
                .accountType(AccountType.PERSONAL)
                .balance(new BigDecimal(balance))
                .currency("VND")
                .status(AccountStatus.ACTIVE)
                .build());
    }

    private TransferPreview preview(
            User owner,
            Account source,
            Account recipient,
            OffsetDateTime expiresAt,
            OffsetDateTime consumedAt
    ) {
        return previewRepository.saveAndFlush(TransferPreview.builder()
                .initiator(owner)
                .sourceAccount(source)
                .destinationAccount(recipient)
                .mode(TransferPreviewMode.EXTERNAL)
                .amount(new BigDecimal("1000.00"))
                .fee(BigDecimal.ZERO.setScale(2))
                .currency("VND")
                .requestFingerprint(UUID.randomUUID().toString())
                .expiresAt(expiresAt)
                .consumedAt(consumedAt)
                .build());
    }

    private String previewBody(Account source, Account recipient, String amount) {
        return """
                {"mode":"EXTERNAL","sourceAccountId":"%s","recipientAccountNumber":"%s","amount":"%s","currency":"VND"}
                """.formatted(source.getId(), recipient.getAccountNumber(), amount);
    }

    private String executeBody(TransferPreview preview) {
        return """
                {"previewId":"%s","idempotencyKey":"%s"}
                """.formatted(preview.getId(), UUID.randomUUID());
    }

    private String accountBody(String accountType, String description, String key) {
        String descriptionJson = description == null ? "null" : "\"" + description + "\"";
        return """
                {"accountType":"%s","currency":"VND","description":%s,"idempotencyKey":"%s"}
                """.formatted(accountType, descriptionJson, key);
    }

    private String tokenFor(User user) {
        return jwtService.generateToken(AuthenticatedUserPrincipal.from(user));
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postWithoutAuthorization(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private void assertForbidden(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("FORBIDDEN");
    }

    private void assertInvalidAmount(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("INVALID_TRANSFER_AMOUNT");
    }

    private void assertPreviewUnavailable(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("TRANSFER_PREVIEW_UNAVAILABLE");
    }
}
