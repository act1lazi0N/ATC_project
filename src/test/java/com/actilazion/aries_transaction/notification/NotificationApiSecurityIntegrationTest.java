package com.actilazion.aries_transaction.notification;

import com.actilazion.aries_transaction.config.JwtService;
import com.actilazion.aries_transaction.identity.application.AuthenticatedUserPrincipal;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.notification.domain.Notification;
import com.actilazion.aries_transaction.notification.domain.NotificationSourceKind;
import com.actilazion.aries_transaction.notification.domain.NotificationType;
import com.actilazion.aries_transaction.notification.infrastructure.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest
@ActiveProfiles("test")
class NotificationApiSecurityIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired JwtService jwtService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void notificationEndpointsRequireAuthenticationAndReturnNoStore() throws Exception {
        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isUnauthorized());

        User user = user(Role.USER);
        mockMvc.perform(get("/api/v1/notifications").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void foreignNotificationIdIsConcealedAsNotFound() throws Exception {
        User owner = user(Role.USER);
        User other = user(Role.USER);
        Notification notification = notificationRepository.saveAndFlush(Notification.builder()
                .id(UUID.randomUUID()).recipient(owner)
                .sourceKind(NotificationSourceKind.OUTBOX_EVENT).sourceId(UUID.randomUUID()).sourceVersion(0)
                .type(NotificationType.TRANSFER_COMPLETED)
                .title("Transfer completed").message("Safe message").payload(Map.of("amount", "1.00"))
                .occurredAt(OffsetDateTime.now()).build());

        mockMvc.perform(patch("/api/v1/notifications/{id}/read", notification.getId())
                        .header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
    }

    @Test
    void verificationConfirmIsPublicButRequestIsProtected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/email-verification/request"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/email-verification/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"invalid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Email verification token is invalid or expired"));
    }

    @Test
    void customerCannotReadOperationsEmailQueue() throws Exception {
        mockMvc.perform(get("/api/v1/operations/notification-email-deliveries")
                        .header("Authorization", bearer(user(Role.USER))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/operations/notification-email-deliveries")
                        .header("Authorization", bearer(user(Role.OPERATOR))))
                .andExpect(status().isOk());
    }

    private User user(Role role) {
        String id = UUID.randomUUID().toString();
        return userRepository.saveAndFlush(User.builder()
                .fullName("Notification security")
                .email("notification-security-" + id + "@test.local")
                .passwordHash("not-used")
                .role(role)
                .isActive(true)
                .build());
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.generateToken(AuthenticatedUserPrincipal.from(user));
    }
}
