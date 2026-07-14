package com.actilazion.aries_transaction.security;

import com.actilazion.aries_transaction.config.JwtService;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SecurityFilterIntegrationTest {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    UserRepository userRepository;

    @Autowired
    JwtService jwtService;

    @Test
    void settlementCreate_userRole_forbidden() throws Exception {
        String token = tokenFor(savedUser(Role.USER, true));

        HttpResponse<String> response = post("/api/v1/settlements/batches", token, """
                {
                  "currency": "VND",
                  "feeRateBps": 200,
                  "idempotencyKey": "settlement-security-key",
                  "cutoffCompletedAt": "2026-07-14T00:00:00Z"
                }
                """);

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void reconciliationCreate_userRole_forbidden() throws Exception {
        String token = tokenFor(savedUser(Role.USER, true));

        HttpResponse<String> response = post("/api/v1/reconciliation/runs", token, """
                {
                  "currency": "VND",
                  "windowStart": "2026-07-13T00:00:00Z",
                  "windowEnd": "2026-07-14T00:00:00Z"
                }
                """);

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void protectedEndpoint_inactiveUserToken_unauthorized() throws Exception {
        String token = tokenFor(savedUser(Role.USER, false));

        HttpResponse<String> response = get("/api/v1/transfers/" + UUID.randomUUID(), token);

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void protectedEndpoint_malformedBearerToken_unauthorized() throws Exception {
        HttpResponse<String> response = get("/api/v1/transfers/" + UUID.randomUUID(), "not-a-jwt");

        assertThat(response.statusCode()).isEqualTo(401);
    }

    private User savedUser(Role role, boolean active) {
        return userRepository.save(User.builder()
                .fullName("Security Test User")
                .email("security-" + UUID.randomUUID() + "@test.local")
                .passwordHash("unused")
                .role(role)
                .isActive(active)
                .build());
    }

    private String tokenFor(User user) {
        UserDetails principal = org.springframework.security.core.userdetails.User
                .withUsername(user.getEmail())
                .password(user.getPasswordHash())
                .roles(user.getRole().name())
                .build();
        return jwtService.generateToken(principal);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Authorization", bearer(token))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Authorization", bearer(token))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
