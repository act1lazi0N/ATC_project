package com.actilazion.aries_transaction.security;

import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.identity.infrastructure.RefreshSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest
@ActiveProfiles("test")
class SuspendedLoginSecurityMockMvcTest {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired RefreshSessionRepository sessions;
    @Autowired PasswordEncoder passwords;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void correctPasswordReportsSuspensionWithoutSessionAndReactivationRestoresLogin() throws Exception {
        User user = suspendedUser();
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(user.getEmail(), "suspended-password-123")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"))
                .andExpect(jsonPath("$.message").value("Your account is suspended. Contact support for help."))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(sessions.countByUserId(user.getId())).isZero();
        User persisted = users.findById(user.getId()).orElseThrow();
        assertThat(persisted.getFailedLoginAttempts()).isZero();
        assertThat(persisted.getIsActive()).isFalse();

        persisted.setIsActive(true);
        users.saveAndFlush(persisted);
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(user.getEmail(), "suspended-password-123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isString());
        assertThat(sessions.countByUserId(user.getId())).isEqualTo(1);
    }

    @Test
    void wrongPasswordAndUnknownEmailRemainGeneric() throws Exception {
        User user = suspendedUser();
        for (String email : new String[] { user.getEmail(), "missing-" + UUID.randomUUID() + "@test.local" }) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(credentials(email, "wrong-password")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                    .andExpect(jsonPath("$.message").value("Unauthorized"))
                    .andExpect(header().doesNotExist("Set-Cookie"));
        }
        assertThat(users.findById(user.getId()).orElseThrow().getFailedLoginAttempts()).isEqualTo(1);
        assertThat(sessions.countByUserId(user.getId())).isZero();
    }

    @Test
    void temporaryLockoutStillBlocksCorrectPassword() throws Exception {
        User user = suspendedUser();
        user.setLockedUntil(OffsetDateTime.now().plusMinutes(5));
        users.saveAndFlush(user);
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(user.getEmail(), "suspended-password-123")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(sessions.countByUserId(user.getId())).isZero();
    }

    private User suspendedUser() {
        return users.saveAndFlush(User.builder().fullName("Suspended Login Test")
                .email("suspended-" + UUID.randomUUID() + "@test.local")
                .passwordHash(passwords.encode("suspended-password-123"))
                .isActive(false).build());
    }

    private String credentials(String email, String password) {
        return """
                {"email":"%s","password":"%s"}
                """.formatted(email, password);
    }
}
