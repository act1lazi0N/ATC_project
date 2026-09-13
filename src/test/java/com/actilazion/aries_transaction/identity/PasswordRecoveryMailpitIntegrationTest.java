package com.actilazion.aries_transaction.identity;

import com.actilazion.aries_transaction.identity.application.AuthService;
import com.actilazion.aries_transaction.identity.dto.LoginRequest;
import com.actilazion.aries_transaction.identity.dto.RegisterRequest;
import com.actilazion.aries_transaction.notification.application.*;
import com.actilazion.aries_transaction.notification.infrastructure.email.EmailDeliveryWorker;
import com.actilazion.aries_transaction.notification.infrastructure.email.EmailGateway;
import com.actilazion.aries_transaction.support.PostgresIntegrationTestSupport;
import com.actilazion.aries_transaction.support.TestSecrets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest
@ActiveProfiles("test")
class PasswordRecoveryMailpitIntegrationTest extends PostgresIntegrationTestSupport {
    private static final String RESET_KEY = TestSecrets.newBase64Key();

    @Container
    static final GenericContainer<?> MAILPIT = new GenericContainer<>("axllent/mailpit:v1.27")
            .withExposedPorts(1025, 8025);

    @DynamicPropertySource
    static void mailProperties(DynamicPropertyRegistry registry) {
        registry.add("security.account-security.enabled", () -> true);
        registry.add("security.account-security.reset-signing-key", () -> RESET_KEY);
        registry.add("security.account-security.reset-public-url", () -> "http://localhost:3000/reset-password");
        registry.add("app.notification.email.mode", () -> "smtp");
        registry.add("app.notification.email.from", () -> "no-reply@aries.test");
        // Drive the actual worker explicitly so assertions observe each durable stage.
        registry.add("app.notification.email.worker-enabled", () -> false);
        registry.add("spring.mail.host", MAILPIT::getHost);
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
        registry.add("spring.mail.properties.mail.smtp.auth", () -> false);
        registry.add("spring.mail.properties.mail.smtp.starttls.enable", () -> false);
        registry.add("spring.mail.properties.mail.smtp.starttls.required", () -> false);
    }

    @Autowired WebApplicationContext context;
    @Autowired AuthService auth;
    @Autowired EmailDeliveryService emailService;
    @Autowired EmailGateway gateway;
    @Autowired NotificationProperties properties;
    @Autowired NotificationMetrics metrics;
    @Autowired JdbcTemplate jdbc;

    @Test
    void capturedSmtpLinkResetsPasswordAndQueuesConfirmationWithoutLeakingSecrets() throws Exception {
        var mvc = webAppContextSetup(context).apply(springSecurity()).build();
        String email = UUID.randomUUID() + "@test.local";
        String oldPassword = "mailpit-original-password";
        String newPassword = "mailpit-replacement-password";
        var session = auth.register(new RegisterRequest("Mailpit Recovery", email, oldPassword));
        mvc.perform(post("/api/v1/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_deliveries WHERE purpose = 'PASSWORD_RESET' AND status = 'PENDING'", Long.class))
                .isEqualTo(1);

        var worker = new EmailDeliveryWorker(emailService, gateway, properties, metrics);
        worker.deliverPending();
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        var client = RestClient.builder().requestFactory(requestFactory)
                .baseUrl("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025)).build();
        var mapper = JsonMapper.builder().build();
        JsonNode mailbox = mapper.readTree(client.get().uri("/api/v1/messages").retrieve().body(String.class));
        assertThat(mailbox.path("messages").size()).isEqualTo(1);
        String messageId = mailbox.path("messages").get(0).path("ID").asString();
        JsonNode message = mapper.readTree(client.get().uri("/api/v1/message/" + messageId).retrieve().body(String.class));
        assertThat(message.path("To").get(0).path("Address").asString()).isEqualTo(email);
        String text = message.path("Text").asString();
        String url = text.substring(text.indexOf("http://"), text.indexOf('\n')).trim();
        String token = URLDecoder.decode(URI.create(url).getRawQuery().substring("token=".length()), StandardCharsets.UTF_8);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_deliveries WHERE purpose = 'PASSWORD_RESET' AND status = 'DELIVERED'", Long.class))
                .isEqualTo(1);

        mvc.perform(post("/api/v1/auth/reset-password").header("Origin", "http://localhost:3000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(java.util.Map.of("token", token, "newPassword", newPassword))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accessToken").doesNotExist());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isUnauthorized());
        assertThatThrownBy(() -> auth.refresh(session.refreshToken())).hasMessage("Unauthorized");
        assertThat(auth.login(new LoginRequest(email, newPassword)).accessToken()).isNotBlank();

        worker.deliverPending();
        JsonNode confirmedMailbox = mapper.readTree(client.get().uri("/api/v1/messages").retrieve().body(String.class));
        assertThat(confirmedMailbox.path("messages").size()).isEqualTo(2);
        String confirmationId = confirmedMailbox.path("messages").get(0).path("ID").asString();
        JsonNode confirmation = mapper.readTree(client.get().uri("/api/v1/message/" + confirmationId).retrieve().body(String.class));
        assertThat(confirmation.path("Subject").asString()).isEqualTo("Your Aries password was changed");
        assertThat(confirmation.path("Text").asString()).doesNotContain(token, oldPassword, newPassword);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_deliveries WHERE status = 'DELIVERED'", Long.class)).isEqualTo(2);
    }
}
