package com.actilazion.aries_transaction.identity.application;

import com.actilazion.aries_transaction.common.exception.AppException;
import com.actilazion.aries_transaction.common.exception.ResourceNotFoundException;
import com.actilazion.aries_transaction.audit.application.IdentityAuditService;
import com.actilazion.aries_transaction.audit.domain.IdentityAuditEventType;
import com.actilazion.aries_transaction.config.JwtConfig;
import com.actilazion.aries_transaction.config.JwtService;
import com.actilazion.aries_transaction.config.LoginProtectionConfig;
import com.actilazion.aries_transaction.identity.domain.RefreshSession;
import com.actilazion.aries_transaction.identity.domain.RefreshSessionRevocationReason;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.dto.AuthResponse;
import com.actilazion.aries_transaction.identity.dto.LoginRequest;
import com.actilazion.aries_transaction.identity.dto.RegisterRequest;
import com.actilazion.aries_transaction.identity.dto.UserResponse;
import com.actilazion.aries_transaction.identity.infrastructure.RefreshSessionRepository;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.common.redis.SecurityKeyHasher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtConfig jwtConfig;
    private final AuthenticationManager authenticationManager;
    private final RefreshSessionRepository refreshSessionRepository;
    private final LoginProtectionConfig loginProtectionConfig;
    private final LoginAttemptStore loginAttemptStore;
    private final SecurityKeyHasher securityKeyHasher;
    private final IdentityAuditService identityAuditService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    @Override
    public AuthResponse register(RegisterRequest request) {
        return register(request, null);
    }

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request, String ipAddress) {
        String email = normalizeEmail(request.email());
        validatePasswordLength(request.password());
        if (userRepository.existsByEmail(email)) {
            identityAuditService.record(IdentityAuditEventType.REGISTRATION_REJECTED, null, email, ipAddress, Map.of());
            throw registrationConflict();
        }

        User user = User.builder()
                .fullName(request.fullName())
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .build();
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            if (!isDuplicateEmailViolation(ex)) {
                throw ex;
            }
            identityAuditService.record(IdentityAuditEventType.REGISTRATION_REJECTED, null, email, ipAddress, Map.of());
            throw registrationConflict();
        }
        log.info("[AUTH] User registered id: {}", user.getId());
        identityAuditService.record(IdentityAuditEventType.REGISTERED, user.getId(), email, ipAddress, Map.of());

        return issueSession(user);
    }

    @Override
    @Transactional(noRollbackFor = AuthenticationException.class)
    public AuthResponse login(LoginRequest request) {
        return login(request, null);
    }

    @Override
    @Transactional(noRollbackFor = AuthenticationException.class)
    public AuthResponse login(LoginRequest request, String ipAddress) {
        String email = normalizeEmail(request.email());
        validatePasswordLength(request.password());
        loginAttemptStore.ensureAvailable();
        User user = userRepository.findByEmailWithLock(email).orElse(null);
        if (user != null && isLocked(user, OffsetDateTime.now())) {
            identityAuditService.record(IdentityAuditEventType.LOGIN_LOCKED, user.getId(), email, ipAddress, Map.of());
            throw unauthorized();
        }

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, request.password())
            );
        } catch (AuthenticationException ex) {
            if (user != null) {
                boolean locked = recordFailedLogin(user, email, OffsetDateTime.now());
                userRepository.save(user);
                identityAuditService.record(locked ? IdentityAuditEventType.LOGIN_LOCKED : IdentityAuditEventType.LOGIN_FAILED,
                        user.getId(), email, ipAddress, Map.of());
            } else {
                identityAuditService.record(IdentityAuditEventType.LOGIN_FAILED, null, email, ipAddress, Map.of());
            }
            throw ex;
        }

        user = userRepository.findByEmailWithLock(email).orElseThrow(this::unauthorized);
        loginAttemptStore.clear(loginAttemptKey(email, user));
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);
        identityAuditService.record(IdentityAuditEventType.LOGIN_SUCCEEDED, user.getId(), email, ipAddress, Map.of());
        return issueSession(user);
    }

    @Override
    @Transactional(noRollbackFor = AppException.class)
    public AuthResponse refresh(String refreshToken) {
        return refresh(refreshToken, null);
    }

    @Override
    @Transactional(noRollbackFor = AppException.class)
    public AuthResponse refresh(String refreshToken, String ipAddress) {
        if (refreshToken == null || refreshToken.isBlank()) {
            identityAuditService.record(IdentityAuditEventType.REFRESH_REJECTED, null, null, ipAddress, Map.of());
            throw unauthorized();
        }

        RefreshSession current = refreshSessionRepository.findByTokenHashForUpdate(hash(refreshToken)).orElse(null);
        if (current == null) {
            identityAuditService.record(IdentityAuditEventType.REFRESH_REJECTED, null, null, ipAddress, Map.of());
            throw unauthorized();
        }
        OffsetDateTime now = OffsetDateTime.now();
        User user = current.getUser();

        if (current.getRevokedAt() != null) {
            if (current.getRevokedReason() == RefreshSessionRevocationReason.ROTATED
                    || current.getRevokedReason() == null) {
                refreshSessionRepository.revokeActiveByFamilyId(
                        current.getFamilyId(), now, RefreshSessionRevocationReason.SECURITY_REUSE);
                identityAuditService.record(IdentityAuditEventType.REFRESH_REUSE_DETECTED,
                        user.getId(), null, ipAddress, Map.of());
            } else {
                identityAuditService.record(IdentityAuditEventType.REFRESH_REJECTED,
                        user.getId(), null, ipAddress, Map.of());
            }
            throw unauthorized();
        }
        if (current.getExpiresAt().isBefore(now) || !Boolean.TRUE.equals(user.getIsActive())) {
            current.setRevokedAt(now);
            current.setRevokedReason(Boolean.TRUE.equals(user.getIsActive())
                    ? RefreshSessionRevocationReason.EXPIRED
                    : RefreshSessionRevocationReason.ADMIN_REVOKED);
            identityAuditService.record(IdentityAuditEventType.REFRESH_REJECTED, user.getId(), null, ipAddress, Map.of());
            throw unauthorized();
        }

        current.setLastUsedAt(now);
        current.setRevokedAt(now);
        current.setRevokedReason(RefreshSessionRevocationReason.ROTATED);
        String rotatedToken = newRefreshToken();
        RefreshSession replacement = newSession(user, rotatedToken, now, current.getFamilyId());
        refreshSessionRepository.save(replacement);
        current.setReplacedBy(replacement);
        refreshSessionRepository.save(current);
        identityAuditService.record(IdentityAuditEventType.REFRESH_SUCCEEDED, user.getId(), null, ipAddress, Map.of());

        return AuthResponse.withRefresh(
                jwtService.generateToken(AuthenticatedUserPrincipal.from(user)),
                jwtConfig.getExpiration(), UserResponse.from(user), rotatedToken);
    }

    @Override
    @Transactional
    public void logout(String refreshToken) {
        OffsetDateTime now = OffsetDateTime.now();
        RefreshSession session = refreshToken == null || refreshToken.isBlank()
                ? null
                : refreshSessionRepository.findByTokenHashForUpdate(hash(refreshToken)).orElse(null);
        if (session != null) {
            refreshSessionRepository.revokeActiveByFamilyId(
                    session.getFamilyId(), now, RefreshSessionRevocationReason.LOGOUT);
        }
        identityAuditService.record(IdentityAuditEventType.LOGOUT,
                session == null ? null : session.getUser().getId(), null, null, Map.of());
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse me(UUID userId) {
        return userRepository.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }

    private AuthResponse issueSession(User user) {
        String refreshToken = newRefreshToken();
        refreshSessionRepository.save(newSession(user, refreshToken, OffsetDateTime.now(), UUID.randomUUID()));
        return AuthResponse.withRefresh(
                jwtService.generateToken(AuthenticatedUserPrincipal.from(user)),
                jwtConfig.getExpiration(), UserResponse.from(user), refreshToken);
    }

    private RefreshSession newSession(User user, String refreshToken, OffsetDateTime now, UUID familyId) {
        return RefreshSession.builder()
                .user(user)
                .refreshTokenHash(hash(refreshToken))
                .familyId(familyId)
                .expiresAt(now.plusSeconds(jwtConfig.getRefreshExpiration()))
                .build();
    }

    private String newRefreshToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private AppException unauthorized() {
        return new AppException("Unauthorized", HttpStatus.UNAUTHORIZED) {};
    }

    private boolean recordFailedLogin(User user, String email, OffsetDateTime now) {
        long attempts = loginAttemptStore.recordFailure(
                loginAttemptKey(email, user),
                Duration.ofSeconds(loginProtectionConfig.getLockDurationSeconds())
        );
        user.setFailedLoginAttempts((int) Math.min(attempts, loginProtectionConfig.getMaxFailedAttempts()));
        if (attempts >= loginProtectionConfig.getMaxFailedAttempts()) {
            user.setLockedUntil(now.plusSeconds(loginProtectionConfig.getLockDurationSeconds()));
            log.warn("[AUTH] Login temporarily locked userId={}", user.getId());
            return true;
        }
        return false;
    }

    private String loginAttemptKey(String email, User user) {
        if (user != null && user.getId() != null) {
            return "user:" + user.getId();
        }
        return "email:" + securityKeyHasher.hash(email);
    }

    private void validatePasswordLength(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new AppException("Password must not exceed 72 UTF-8 bytes", HttpStatus.BAD_REQUEST) {};
        }
    }

    private boolean isLocked(User user, OffsetDateTime now) {
        if (user.getLockedUntil() == null) {
            return false;
        }
        if (user.getLockedUntil().isAfter(now)) {
            return true;
        }
        user.setLockedUntil(null);
        user.setFailedLoginAttempts(0);
        userRepository.save(user);
        return false;
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private AppException registrationConflict() {
        return new AppException("Registration request cannot be completed", HttpStatus.CONFLICT) {};
    }

    private boolean isDuplicateEmailViolation(DataIntegrityViolationException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof SQLException sqlException && "23505".equals(sqlException.getSQLState())) {
                return true;
            }
            String message = cause.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("uk_users_email")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
