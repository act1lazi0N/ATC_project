package com.actilazion.aries_transaction.operations.application;

import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.audit.application.IdentityAuditService;
import com.actilazion.aries_transaction.audit.domain.IdentityAuditEventType;
import com.actilazion.aries_transaction.common.dto.PageResponse;
import com.actilazion.aries_transaction.common.exception.CustomerVersionConflictException;
import com.actilazion.aries_transaction.common.exception.ResourceNotFoundException;
import com.actilazion.aries_transaction.identity.domain.RefreshSessionRevocationReason;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.RefreshSessionRepository;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.operations.dto.CustomerAccountResponse;
import com.actilazion.aries_transaction.operations.dto.CustomerDetailResponse;
import com.actilazion.aries_transaction.operations.dto.CustomerStatus;
import com.actilazion.aries_transaction.operations.dto.CustomerSummaryResponse;
import com.actilazion.aries_transaction.operations.dto.UpdateCustomerStatusRequest;
import com.actilazion.aries_transaction.transaction.application.TransactionReadProjection;
import com.actilazion.aries_transaction.transaction.infrastructure.TransactionRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomerOperationsService {
    private static final List<Role> CUSTOMER_ROLES = List.of(Role.USER, Role.MERCHANT);

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final RefreshSessionRepository refreshSessionRepository;
    private final IdentityAuditService identityAuditService;

    @Transactional(readOnly = true)
    public PageResponse<CustomerSummaryResponse> findCustomers(
            UUID actorId,
            String search,
            Role role,
            CustomerStatus status,
            Pageable pageable
    ) {
        requireStaff(actorId);
        if (role != null && !CUSTOMER_ROLES.contains(role)) {
            throw new IllegalArgumentException("role must be USER or MERCHANT");
        }
        var page = userRepository.findAll((root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(root.get("role").in(role == null ? CUSTOMER_ROLES : List.of(role)));
            if (status != null) {
                predicates.add(builder.equal(root.get("isActive"), status == CustomerStatus.ACTIVE));
            }
            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("fullName")), pattern),
                        builder.like(builder.lower(root.get("email")), pattern)
                ));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        }, pageable).map(CustomerSummaryResponse::from);
        return PageResponse.from(page);
    }

    @Transactional(readOnly = true)
    public CustomerDetailResponse getCustomer(UUID actorId, UUID customerId) {
        User actor = requireStaff(actorId);
        User target = requireCustomer(customerId);
        var accounts = accountRepository.findAllByUserId(target.getId()).stream()
                .map(CustomerAccountResponse::from)
                .toList();
        var activity = transactionRepository.findRecentByUserId(
                        target.getId(),
                        PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt", "id"))
                ).stream()
                .map(transaction -> TransactionReadProjection.project(transaction, actor, null))
                .toList();
        return new CustomerDetailResponse(CustomerSummaryResponse.from(target), accounts, activity);
    }

    @Transactional
    public CustomerSummaryResponse updateStatus(
            UUID actorId,
            UUID customerId,
            UpdateCustomerStatusRequest request,
            String ipAddress
    ) {
        User actor = requireStaff(actorId);
        if (actorId.equals(customerId)) {
            throw new AccessDeniedException("Staff cannot change their own status");
        }
        User target = userRepository.findByIdWithLock(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", customerId));
        ensureCustomerRole(target);
        if (target.getVersion() != request.expectedVersion()) {
            throw new CustomerVersionConflictException();
        }

        boolean active = request.status() == CustomerStatus.ACTIVE;
        if (Boolean.TRUE.equals(target.getIsActive()) == active) {
            return CustomerSummaryResponse.from(target);
        }

        target.setIsActive(active);
        User saved = userRepository.saveAndFlush(target);
        if (!active) {
            refreshSessionRepository.revokeActiveByUserId(
                    target.getId(), OffsetDateTime.now(), RefreshSessionRevocationReason.ADMIN_REVOKED);
        }
        identityAuditService.recordCustomerAdministration(
                active ? IdentityAuditEventType.CUSTOMER_REACTIVATED : IdentityAuditEventType.CUSTOMER_SUSPENDED,
                target.getId(),
                actor.getId(),
                ipAddress,
                Map.of("reason", request.reason().trim())
        );
        return CustomerSummaryResponse.from(saved);
    }

    private User requireStaff(UUID actorId) {
        User actor = userRepository.findById(actorId)
                .orElseThrow(() -> new ResourceNotFoundException("User", actorId));
        if (actor.getRole() != Role.OPERATOR && actor.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("Caller is not authorized for customer operations");
        }
        return actor;
    }

    private User requireCustomer(UUID customerId) {
        User customer = userRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", customerId));
        ensureCustomerRole(customer);
        return customer;
    }

    private void ensureCustomerRole(User target) {
        if (!CUSTOMER_ROLES.contains(target.getRole())) {
            throw new AccessDeniedException("Staff identities cannot be managed as customers");
        }
    }
}
