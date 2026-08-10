package com.actilazion.aries_transaction.identity.application;

import com.actilazion.aries_transaction.common.exception.AppException;
import com.actilazion.aries_transaction.common.exception.ResourceNotFoundException;
import com.actilazion.aries_transaction.config.JwtConfig;
import com.actilazion.aries_transaction.config.JwtService;
import com.actilazion.aries_transaction.config.LoginProtectionConfig;
import com.actilazion.aries_transaction.identity.domain.RefreshSession;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.dto.AuthResponse;
import com.actilazion.aries_transaction.identity.dto.LoginRequest;
import com.actilazion.aries_transaction.identity.dto.RegisterRequest;
import com.actilazion.aries_transaction.identity.dto.UserResponse;
import com.actilazion.aries_transaction.identity.infrastructure.RefreshSessionRepository;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

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
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    @Override
    public AuthResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new AppException("Email already registered", HttpStatus.CONFLICT) {};
        }

        User user = User.builder()
                .fullName(request.fullName())
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .build();
        userRepository.save(user);
        log.info("[AUTH] User registered id: {}", user.getId());

        return issueSession(user);
    }

    @Override
    @Transactional(noRollbackFor = AuthenticationException.class)
    public AuthResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        User user = userRepository.findByEmailWithLock(email).orElse(null);
        if (user != null && isLocked(user, OffsetDateTime.now())) {
            throw unauthorized();
        }

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, request.password())
            );
        } catch (AuthenticationException ex) {
            if (user != null) {
                recordFailedLogin(user, OffsetDateTime.now());
                userRepository.save(user);
            }
            throw ex;
        }

        user = userRepository.findByEmailWithLock(email).orElseThrow(this::unauthorized);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);
        return issueSession(user);
    }

    @Override
    @Transactional
    public AuthResponse refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw unauthorized();
        }

        RefreshSession current = refreshSessionRepository.findByTokenHashForUpdate(hash(refreshToken))
                .orElseThrow(this::unauthorized);
        OffsetDateTime now = OffsetDateTime.now();
        User user = current.getUser();

        if (current.getRevokedAt() != null) {
            refreshSessionRepository.revokeActiveByUserId(user.getId(), now);
            throw unauthorized();
        }
        if (current.getExpiresAt().isBefore(now) || !Boolean.TRUE.equals(user.getIsActive())) {
            current.setRevokedAt(now);
            throw unauthorized();
        }

        current.setLastUsedAt(now);
        current.setRevokedAt(now);
        String rotatedToken = newRefreshToken();
        RefreshSession replacement = newSession(user, rotatedToken, now);
        refreshSessionRepository.save(replacement);
        current.setReplacedBy(replacement);
        refreshSessionRepository.save(current);

        return AuthResponse.withRefresh(
                jwtService.generateToken(AuthenticatedUserPrincipal.from(user)),
                jwtConfig.getExpiration(), UserResponse.from(user), rotatedToken);
    }

    @Override
    @Transactional
    public void logout(UUID userId, String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        refreshSessionRepository.findByTokenHashForUpdate(hash(refreshToken))
                .filter(session -> session.getUser().getId().equals(userId))
                .ifPresent(session -> session.setRevokedAt(OffsetDateTime.now()));
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
        refreshSessionRepository.save(newSession(user, refreshToken, OffsetDateTime.now()));
        return AuthResponse.withRefresh(
                jwtService.generateToken(AuthenticatedUserPrincipal.from(user)),
                jwtConfig.getExpiration(), UserResponse.from(user), refreshToken);
    }

    private RefreshSession newSession(User user, String refreshToken, OffsetDateTime now) {
        return RefreshSession.builder()
                .user(user)
                .refreshTokenHash(hash(refreshToken))
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

    private void recordFailedLogin(User user, OffsetDateTime now) {
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);
        if (attempts >= loginProtectionConfig.getMaxFailedAttempts()) {
            user.setLockedUntil(now.plusSeconds(loginProtectionConfig.getLockDurationSeconds()));
            log.warn("[AUTH] Login temporarily locked userId={}", user.getId());
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
}
