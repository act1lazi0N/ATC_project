package com.actilazion.aries_transaction.identity.application;

import com.actilazion.aries_transaction.identity.domain.RefreshSessionRevocationReason;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.domain.exception.UnauthorizedException;
import com.actilazion.aries_transaction.identity.infrastructure.RefreshSessionRepository;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class SessionRevocationService {
    private final UserRepository users;
    private final RefreshSessionRepository sessions;

    public User lockAuthenticatedUser(AuthenticatedUserPrincipal principal) {
        User user = users.findByIdWithLock(principal.getUserId()).orElseThrow(UnauthorizedException::new);
        if (!AuthenticatedUserPrincipal.from(user).isEnabled() || user.getAuthVersion() != principal.getAuthVersion()) {
            throw new UnauthorizedException();
        }
        return user;
    }

    /** Caller must hold the user row lock and finish user mutations before this bulk operation. */
    public void revokeAll(User user, RefreshSessionRevocationReason reason, OffsetDateTime now) {
        user.setAuthVersion(Math.incrementExact(user.getAuthVersion()));
        users.saveAndFlush(user);
        sessions.revokeActiveByUserId(user.getId(), now, reason);
    }
}
