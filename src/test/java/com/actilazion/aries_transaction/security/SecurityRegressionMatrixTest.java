package com.actilazion.aries_transaction.security;

import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.domain.AccountStatus;
import com.actilazion.aries_transaction.account.domain.AccountType;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.config.JwtConfig;
import com.actilazion.aries_transaction.config.JwtService;
import com.actilazion.aries_transaction.identity.application.AuthenticatedUserPrincipal;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import javax.crypto.SecretKey;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.math.BigDecimal;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SecurityRegressionMatrixTest {
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired UserRepository userRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired JwtService jwtService;
    @Autowired JwtConfig jwtConfig;

    @Test
    void expiredToken_returns401WithBearerChallenge() throws Exception {
        User user = user(Role.USER, true);
        String token = signed(user, new Date(System.currentTimeMillis() - 20_000),
                new Date(System.currentTimeMillis() - 120_000), null,
                signingKey());

        HttpResponse<String> response = get("/api/v1/auth/me", token);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
        assertThat(response.body()).doesNotContain("JwtException", "signing", token);
    }

    @Test
    void futureNotBeforeToken_returns401() throws Exception {
        User user = user(Role.USER, true);
        String token = signed(user, new Date(),
                new Date(System.currentTimeMillis() + 120_000),
                new Date(System.currentTimeMillis() + 60_000),
                signingKey());

        assertThat(get("/api/v1/auth/me", token).statusCode()).isEqualTo(401);
    }

    @Test
    void wrongKeyToken_returns401() throws Exception {
        User user = user(Role.USER, true);
        String token = signed(user, new Date(),
                new Date(System.currentTimeMillis() + 60_000), null,
                Keys.hmacShaKeyFor(new byte[32]));

        assertThat(get("/api/v1/auth/me", token).statusCode()).isEqualTo(401);
    }

    @Test
    void lockedUserToken_returns401AfterDatabaseStateChange() throws Exception {
        User user = user(Role.USER, true);
        String token = jwtService.generateToken(AuthenticatedUserPrincipal.from(user));
        user.setLockedUntil(java.time.OffsetDateTime.now().plusMinutes(10));
        userRepository.save(user);

        assertThat(get("/api/v1/auth/me", token).statusCode()).isEqualTo(401);
    }

    @Test
    void userCannotTransferOrReadForeignAccountThroughFilterChain() throws Exception {
        User owner = user(Role.USER, true);
        User caller = user(Role.USER, true);
        Account foreign = account(owner, "600000000001");
        Account own = account(caller, "600000000002");
        String token = jwtService.generateToken(AuthenticatedUserPrincipal.from(caller));

        HttpResponse<String> transfer = post("/api/v1/transfers", token, """
                {"fromAccountId":"%s","toAccountId":"%s","amount":1000,"currency":"VND","idempotencyKey":"%s"}
                """.formatted(foreign.getId(), own.getId(), UUID.randomUUID()));
        HttpResponse<String> history = get("/api/v1/transfers/account/" + foreign.getId(), token);

        assertThat(transfer.statusCode()).isEqualTo(403);
        assertThat(history.statusCode()).isEqualTo(403);
    }

    private User user(Role role, boolean active) {
        return userRepository.save(User.builder()
                .fullName("Security Matrix User")
                .email("matrix-" + UUID.randomUUID() + "@test.local")
                .passwordHash("not-used")
                .role(role)
                .isActive(active)
                .build());
    }

    private Account account(User owner, String number) {
        return accountRepository.save(Account.builder()
                .user(owner)
                .accountNumber(number)
                .accountType(AccountType.PERSONAL)
                .balance(new BigDecimal("1000.00"))
                .currency("VND")
                .status(AccountStatus.ACTIVE)
                .build());
    }

    private String signed(User user, Date issuedAt, Date expiration, Date notBefore, SecretKey key) {
        var builder = Jwts.builder()
                .setSubject(user.getId().toString())
                .setIssuer(jwtConfig.getIssuer())
                .setAudience(jwtConfig.getAudience())
                .setId(UUID.randomUUID().toString())
                .setIssuedAt(issuedAt)
                .setExpiration(expiration)
                .claim("typ", jwtConfig.getTokenType())
                .signWith(key, SignatureAlgorithm.HS256);
        if (notBefore != null) {
            builder.setNotBefore(notBefore);
        }
        return builder.compact();
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtConfig.getSecret()));
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
