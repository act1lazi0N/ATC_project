package com.actilazion.aries_transaction.operations.application;

import com.actilazion.aries_transaction.account.application.AccountPartyMasking;
import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.common.exception.ResourceNotFoundException;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.ledger.domain.LedgerDirection;
import com.actilazion.aries_transaction.ledger.domain.LedgerEntry;
import com.actilazion.aries_transaction.ledger.domain.LedgerEntryType;
import com.actilazion.aries_transaction.ledger.infrastructure.LedgerEntryRepository;
import com.actilazion.aries_transaction.operations.dto.LedgerEntryPageResponse;
import com.actilazion.aries_transaction.operations.dto.LedgerEntryResponse;
import com.actilazion.aries_transaction.operations.dto.LedgerJournalResponse;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OperationsLedgerService {
    private final LedgerEntryRepository ledgerEntryRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public LedgerEntryPageResponse findEntries(
            UUID actorId,
            OffsetDateTime from,
            OffsetDateTime to,
            UUID transactionId,
            LedgerEntryType entryType,
            LedgerDirection direction,
            String currency,
            String cursor,
            int limit
    ) {
        requireStaff(actorId);
        Cursor decoded = decodeCursor(cursor);
        int safeLimit = Math.clamp(limit, 1, 100);
        var page = ledgerEntryRepository.findAll((root, query, builder) -> {
            var predicates = new ArrayList<Predicate>();
            if (from != null) predicates.add(builder.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) predicates.add(builder.lessThan(root.get("createdAt"), to));
            if (transactionId != null) predicates.add(builder.equal(root.get("transactionId"), transactionId));
            if (entryType != null) predicates.add(builder.equal(root.get("entryType"), entryType));
            if (direction != null) predicates.add(builder.equal(root.get("direction"), direction));
            if (currency != null && !currency.isBlank()) {
                predicates.add(builder.equal(root.get("currency"), currency.trim().toUpperCase(Locale.ROOT)));
            }
            if (decoded != null) {
                predicates.add(builder.or(
                        builder.lessThan(root.get("createdAt"), decoded.createdAt()),
                        builder.and(
                                builder.equal(root.get("createdAt"), decoded.createdAt()),
                                builder.lessThan(root.get("id"), decoded.id())
                        )
                ));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        }, PageRequest.of(
                0,
                safeLimit + 1,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
        ));

        List<LedgerEntry> fetched = page.getContent();
        boolean hasMore = fetched.size() > safeLimit;
        List<LedgerEntry> visible = hasMore ? fetched.subList(0, safeLimit) : fetched;
        MappingContext context = mappingContext(visible);
        List<LedgerEntryResponse> content = visible.stream()
                .map(entry -> map(entry, context.accounts(), context.balancedByTransaction()))
                .toList();
        String nextCursor = hasMore && !visible.isEmpty()
                ? encodeCursor(visible.getLast())
                : null;
        return new LedgerEntryPageResponse(content, nextCursor, hasMore);
    }

    @Transactional(readOnly = true)
    public LedgerJournalResponse getJournal(UUID actorId, UUID transactionId) {
        requireStaff(actorId);
        List<LedgerEntry> entries = ledgerEntryRepository.findAllByTransactionId(transactionId).stream()
                .sorted(java.util.Comparator.comparing(LedgerEntry::getCreatedAt)
                        .thenComparing(LedgerEntry::getId))
                .toList();
        if (entries.isEmpty()) {
            throw new ResourceNotFoundException("LedgerJournal", transactionId);
        }
        MappingContext context = mappingContext(entries);
        BigDecimal debits = total(entries, LedgerDirection.DEBIT);
        BigDecimal credits = total(entries, LedgerDirection.CREDIT);
        boolean balanced = debits.compareTo(credits) == 0;
        return new LedgerJournalResponse(
                transactionId,
                entries.stream().map(entry -> map(entry, context.accounts(), Map.of(transactionId, balanced))).toList(),
                debits.toPlainString(),
                credits.toPlainString(),
                balanced
        );
    }

    private MappingContext mappingContext(List<LedgerEntry> entries) {
        Map<UUID, Account> accounts = accountRepository.findAllById(
                        entries.stream().map(LedgerEntry::getAccountId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Account::getId, account -> account));
        var transactionIds = entries.stream().map(LedgerEntry::getTransactionId).collect(Collectors.toSet());
        Map<UUID, List<LedgerEntry>> journals = ledgerEntryRepository.findAllByTransactionIdIn(transactionIds)
                .stream().collect(Collectors.groupingBy(LedgerEntry::getTransactionId));
        Map<UUID, Boolean> balanced = new HashMap<>();
        journals.forEach((id, legs) -> balanced.put(
                id, total(legs, LedgerDirection.DEBIT).compareTo(total(legs, LedgerDirection.CREDIT)) == 0));
        return new MappingContext(accounts, balanced);
    }

    private LedgerEntryResponse map(
            LedgerEntry entry,
            Map<UUID, Account> accounts,
            Map<UUID, Boolean> balancedByTransaction
    ) {
        Account account = accounts.get(entry.getAccountId());
        return new LedgerEntryResponse(
                entry.getId(),
                entry.getTransactionId(),
                account == null ? "Unavailable" : AccountPartyMasking.maskedNumber(account),
                entry.getDirection(),
                entry.getAmount().toPlainString(),
                entry.getCurrency(),
                entry.getEntryType(),
                entry.getCreatedAt(),
                Boolean.TRUE.equals(balancedByTransaction.get(entry.getTransactionId()))
        );
    }

    private BigDecimal total(List<LedgerEntry> entries, LedgerDirection direction) {
        return entries.stream()
                .filter(entry -> entry.getDirection() == direction)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String encodeCursor(LedgerEntry entry) {
        String value = entry.getCreatedAt() + "|" + entry.getId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = value.split("\\|", 2);
            return new Cursor(OffsetDateTime.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid ledger cursor");
        }
    }

    private User requireStaff(UUID actorId) {
        User actor = userRepository.findById(actorId)
                .orElseThrow(() -> new ResourceNotFoundException("User", actorId));
        if (actor.getRole() != Role.OPERATOR && actor.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("Caller is not authorized for ledger reads");
        }
        return actor;
    }

    private record Cursor(OffsetDateTime createdAt, UUID id) {}

    private record MappingContext(Map<UUID, Account> accounts, Map<UUID, Boolean> balancedByTransaction) {}
}
