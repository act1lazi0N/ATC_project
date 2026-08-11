package com.actilazion.aries_transaction.security;

import com.actilazion.aries_transaction.audit.domain.IdentityAuditEventType;
import com.actilazion.aries_transaction.audit.domain.IdentityAuditLog;
import com.actilazion.aries_transaction.audit.infrastructure.IdentityAuditLogRepository;
import com.actilazion.aries_transaction.common.exception.AppException;
import com.actilazion.aries_transaction.identity.application.AuthService;
import com.actilazion.aries_transaction.identity.dto.LoginRequest;
import com.actilazion.aries_transaction.identity.dto.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class IdentityAuditIntegrationTest {
    @Autowired
    AuthService authService;

    @Autowired
    IdentityAuditLogRepository auditRepository;

    @Test
    void registerAndFailedLogin_writeIdentityAuditWithoutSecrets() {
        String email = "identity-audit-" + System.nanoTime() + "@test.local";
        var registered = authService.register(new RegisterRequest("Audit User", email, "password-123456"));

        assertThatThrownBy(() -> authService.login(new LoginRequest(email, "wrong-password")))
                .isInstanceOf(Exception.class);

        List<IdentityAuditLog> logs = auditRepository.findAllByUserIdOrderByCreatedAtAsc(registered.user().id());
        assertThat(logs).extracting(IdentityAuditLog::getEventType)
                .contains(IdentityAuditEventType.REGISTERED, IdentityAuditEventType.LOGIN_FAILED);
        assertThat(logs).allSatisfy(log -> {
            assertThat(log.getIdentityHash()).isNotEqualTo(email);
            assertThat(log.getMetadata()).doesNotContainKey("password");
        });
    }

    @Test
    void passwordRejectsMoreThan72Utf8Bytes() {
        String longPassword = "😀".repeat(19);

        assertThatThrownBy(() -> authService.register(new RegisterRequest(
                "Long Password", "long-password-" + System.nanoTime() + "@test.local", longPassword)))
                .isInstanceOf(AppException.class)
                .hasMessage("Password must not exceed 72 UTF-8 bytes");
    }
}
