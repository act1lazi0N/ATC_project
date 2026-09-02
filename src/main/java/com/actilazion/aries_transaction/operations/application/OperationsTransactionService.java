package com.actilazion.aries_transaction.operations.application;

import com.actilazion.aries_transaction.common.dto.PageResponse;
import com.actilazion.aries_transaction.common.exception.ResourceNotFoundException;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.transaction.application.TransactionReadProjection;
import com.actilazion.aries_transaction.transaction.domain.TransactionStatus;
import com.actilazion.aries_transaction.transaction.dto.TransactionResponse;
import com.actilazion.aries_transaction.transaction.infrastructure.TransactionRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OperationsTransactionService {
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public PageResponse<TransactionResponse> findTransactions(
            UUID actorId,
            UUID transactionId,
            TransactionStatus status,
            String currency,
            OffsetDateTime from,
            OffsetDateTime to,
            int page,
            int size
    ) {
        User actor = requireStaff(actorId);
        var transactions = transactionRepository.findAll((root, query, builder) -> {
            var predicates = new ArrayList<Predicate>();
            if (transactionId != null) {
                predicates.add(builder.equal(root.get("id"), transactionId));
            }
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            if (currency != null && !currency.isBlank()) {
                predicates.add(builder.equal(root.get("currency"), currency.trim().toUpperCase(Locale.ROOT)));
            }
            if (from != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(builder.lessThan(root.get("createdAt"), to));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        }, PageRequest.of(
                Math.max(page, 0),
                Math.clamp(size, 1, 100),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
        )).map(transaction -> TransactionReadProjection.project(transaction, actor, null));
        return PageResponse.from(transactions);
    }

    private User requireStaff(UUID actorId) {
        User actor = userRepository.findById(actorId)
                .orElseThrow(() -> new ResourceNotFoundException("User", actorId));
        if (actor.getRole() != Role.OPERATOR && actor.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("Caller is not authorized for operations transactions");
        }
        return actor;
    }
}
