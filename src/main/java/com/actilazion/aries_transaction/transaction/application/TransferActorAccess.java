package com.actilazion.aries_transaction.transaction.application;

import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.common.exception.ResourceNotFoundException;
import com.actilazion.aries_transaction.identity.application.AuthenticatedUserPrincipal;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TransferActorAccess {
    private final UserRepository userRepository;

    public User lockInitiator(String initiatorEmail) {
        User initiator = userRepository.findByEmailWithLock(initiatorEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User", initiatorEmail));
        if (!AuthenticatedUserPrincipal.from(initiator).isEnabled()) {
            throw new AccessDeniedException("User is not active");
        }
        return initiator;
    }

    public void assertOwnsAccount(Account account, User initiator) {
        if (!account.getUser().getId().equals(initiator.getId())) {
            throw new AccessDeniedException("Caller is not authorized for this account");
        }
    }
}
