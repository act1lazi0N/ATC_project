package com.actilazion.aries_transaction.security;

import com.actilazion.aries_transaction.config.JwtService;
import com.actilazion.aries_transaction.config.JwtConfig;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.application.AuthenticatedUserPrincipal;
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
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SecurityFilterIntegrationTest {
    private static final String TEST_BCRYPT_HASH =
            "$2a$12$WztQTc7LCCT3xLMs9D52te6w0kG56E41g9vRDSqD5jHiGTUWUPRx2";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    UserRepository userRepository;

    @Autowired
    JwtService jwtService;

    @Autowired
    JwtConfig jwtConfig;

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

    @Test
    void protectedEndpoint_missingAuthorization_unauthorized() throws Exception {
        HttpResponse<String> response = getWithoutAuthorization("/api/v1/transfers/" + UUID.randomUUID());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void cors_preflightFromFrontend_isAllowedWithoutAuthentication() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type, authorization")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:3000");
        assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).contains("true");
    }

    @Test
    void cors_unauthenticatedLoginResponse_isReadableByFrontend() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                .header("Origin", "http://localhost:3000")
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"email":"missing-user@test.local","password":"wrong-password"}
                        """))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:3000");
        assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).contains("true");
    }

    @Test
    void login_badCredentials_unauthorized() throws Exception {
        User user = savedUser(Role.USER, true);

        HttpResponse<String> response = postWithoutAuthorization("/api/v1/auth/login", """
                {
                  "email": "%s",
                  "password": "wrong-password"
                }
                """.formatted(user.getEmail()));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"status\":401");
        assertThat(response.body()).contains("\"message\":\"Unauthorized\"");
    }

    @Test
    void register_returnsCompleteUserForFrontendSession() throws Exception {
        String email = "register-response-" + UUID.randomUUID() + "@test.local";

        HttpResponse<String> response = postWithoutAuthorization("/api/v1/auth/register", """
                {
                  "fullName": "Frontend Registration User",
                  "email": "%s",
                  "password": "password-123456"
                }
                """.formatted(email));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("\"email\":\"" + email + "\"");
        assertThat(response.body()).containsPattern("\\\"createdAt\\\":\\\"[^\\\"]+\\\"");
        assertThat(response.headers().firstValue("Set-Cookie")).hasValueSatisfying(cookie ->
                assertThat(cookie).contains("refresh_token=", "HttpOnly"));
    }

    @Test
    void protectedEndpoint_malformedUuid_badRequest() throws Exception {
        String token = tokenFor(savedUser(Role.USER, true));

        HttpResponse<String> response = get("/api/v1/transfers/not-a-uuid", token);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"status\":400");
    }

    @Test
    void reconciliationCreate_invalidWindow_badRequest() throws Exception {
        String token = tokenFor(savedUser(Role.OPERATOR, true));

        HttpResponse<String> response = post("/api/v1/reconciliation/runs", token, """
                {
                  "currency": "VND",
                  "windowStart": "2026-07-14T00:00:00Z",
                  "windowEnd": "2026-07-13T00:00:00Z"
                }
                """);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"status\":400");
        assertThat(response.body()).contains("windowStart must be before windowEnd");
    }

    @Test
    void protectedEndpoint_wrongIssuerToken_unauthorized() throws Exception {
        User user = savedUser(Role.USER, true);

        HttpResponse<String> response = get(
                "/api/v1/transfers/" + UUID.randomUUID(),
                signedToken(user, "wrong-issuer", jwtConfig.getAudience(), jwtConfig.getTokenType())
        );

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void protectedEndpoint_wrongAudienceToken_unauthorized() throws Exception {
        User user = savedUser(Role.USER, true);

        HttpResponse<String> response = get(
                "/api/v1/transfers/" + UUID.randomUUID(),
                signedToken(user, jwtConfig.getIssuer(), "wrong-audience", jwtConfig.getTokenType())
        );

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void protectedEndpoint_wrongTokenType_unauthorized() throws Exception {
        User user = savedUser(Role.USER, true);

        HttpResponse<String> response = get(
                "/api/v1/transfers/" + UUID.randomUUID(),
                signedToken(user, jwtConfig.getIssuer(), jwtConfig.getAudience(), "refresh")
        );

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void protectedEndpoint_tamperedToken_unauthorized() throws Exception {
        String token = tokenFor(savedUser(Role.USER, true));
        String[] parts = token.split("\\.");
        String tamperedPayload = (parts[1].charAt(0) == 'a' ? 'b' : 'a') + parts[1].substring(1);
        String tampered = parts[0] + "." + tamperedPayload + "." + parts[2];

        HttpResponse<String> response = get("/api/v1/transfers/" + UUID.randomUUID(), tampered);

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void me_returnsAuthenticatedUserFromUuidSubject() throws Exception {
        User user = savedUser(Role.USER, true);

        HttpResponse<String> response = get("/api/v1/auth/me", tokenFor(user));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(user.getId().toString());
        assertThat(response.body()).contains(user.getEmail());
    }

    @Test
    void refresh_withoutAllowedOrigin_forbidden() throws Exception {
        HttpResponse<String> response = postWithoutAuthorization("/api/v1/auth/refresh", "{}");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("Invalid refresh request origin");
    }

    @Test
    void refresh_crossOrigin_forbidden() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/auth/refresh"))
                .header("Origin", "https://evil.example")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void duplicateRegistration_doesNotExposeEmailExistence() throws Exception {
        String email = "duplicate-" + UUID.randomUUID() + "@test.local";
        String body = """
                {"fullName":"Registration User","email":"%s","password":"password-123456"}
                """.formatted(email);

        assertThat(postWithoutAuthorization("/api/v1/auth/register", body).statusCode()).isEqualTo(201);
        HttpResponse<String> duplicate = postWithoutAuthorization("/api/v1/auth/register", body);

        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(duplicate.body()).doesNotContain("already registered", email);
        assertThat(duplicate.body()).contains("Registration request cannot be completed");
    }

    private User savedUser(Role role, boolean active) {
        return userRepository.save(User.builder()
                .fullName("Security Test User")
                .email("security-" + UUID.randomUUID() + "@test.local")
                .passwordHash(TEST_BCRYPT_HASH)
                .role(role)
                .isActive(active)
                .build());
    }

    private String tokenFor(User user) {
        return jwtService.generateToken(AuthenticatedUserPrincipal.from(user));
    }

    private String signedToken(User user, String issuer, String audience, String tokenType) {
        long nowMs = System.currentTimeMillis();
        return Jwts.builder()
                .setSubject(user.getEmail())
                .setIssuer(issuer)
                .setAudience(audience)
                .setIssuedAt(new Date(nowMs))
                .setExpiration(new Date(nowMs + jwtConfig.getExpiration() * 1000))
                .claim("typ", tokenType)
                .signWith(
                        Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtConfig.getSecret())),
                        SignatureAlgorithm.HS256
                )
                .compact();
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

    private HttpResponse<String> getWithoutAuthorization(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
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
}
