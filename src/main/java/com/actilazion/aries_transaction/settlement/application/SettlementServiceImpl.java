package com.actilazion.aries_transaction.settlement.application;

import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.common.exception.ResourceNotFoundException;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.ledger.application.LedgerService;
import com.actilazion.aries_transaction.settlement.domain.PayoutStatus;
import com.actilazion.aries_transaction.settlement.domain.SettlementBatch;
import com.actilazion.aries_transaction.settlement.domain.SettlementBatchStatus;
import com.actilazion.aries_transaction.settlement.domain.SettlementItem;
import com.actilazion.aries_transaction.settlement.domain.SettlementItemType;
import com.actilazion.aries_transaction.settlement.dto.SettlementBatchResponse;
import com.actilazion.aries_transaction.settlement.domain.exception.NoSettlementCandidateException;
import com.actilazion.aries_transaction.settlement.domain.exception.SettlementIdempotencyConflictException;
import com.actilazion.aries_transaction.settlement.infrastructure.SettlementBatchRepository;
import com.actilazion.aries_transaction.settlement.infrastructure.SettlementItemRepository;
import com.actilazion.aries_transaction.transaction.domain.Transaction;
import com.actilazion.aries_transaction.transaction.infrastructure.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SettlementServiceImpl implements SettlementService {

    private final TransactionRepository transactionRepository;
    private final SettlementBatchRepository settlementBatchRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final LedgerService ledgerService;
    private final SettlementItemRepository settlementItemRepository;

    @Override
    @Transactional
    public SettlementBatchResponse createBatch(
            String currency,
            int feeRateBps,
            String idempotencyKey,
            OffsetDateTime cutoffCompletedAt,
            String initiatorEmail
    ) {
        assertPrivileged(initiatorEmail);
        String normalizedCurrency = currency.toUpperCase(Locale.ROOT);
        var existing = settlementBatchRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            SettlementBatch batch = existing.get();
            if (!matchesRequest(batch, normalizedCurrency, feeRateBps, cutoffCompletedAt)) {
                throw new SettlementIdempotencyConflictException(idempotencyKey);
            }
            return SettlementBatchResponse.from(batch);
        }

        List<Transaction> candidates = transactionRepository.findSettlementCandidates(normalizedCurrency, cutoffCompletedAt);
        if (candidates.isEmpty()) {
            throw new NoSettlementCandidateException(normalizedCurrency);
        }

        SettlementBatch batch = SettlementBatch.builder()
                .currency(normalizedCurrency)
                .grossAmount(BigDecimal.ZERO)
                .feeAmount(BigDecimal.ZERO)
                .netAmount(BigDecimal.ZERO)
                .feeRateBps(feeRateBps)
                .idempotencyKey(idempotencyKey)
                .cutoffCompletedAt(cutoffCompletedAt)
                .status(SettlementBatchStatus.PENDING)
                .build();

        BigDecimal grossTotal = BigDecimal.ZERO;
        BigDecimal feeTotal = BigDecimal.ZERO;
        BigDecimal netTotal = BigDecimal.ZERO;

        for (Transaction transaction : candidates) {
            SettlementItemType itemType = itemTypeFor(transaction);
            SettlementAmounts amounts = amountsFor(transaction, feeRateBps, itemType);

            SettlementItem item = SettlementItem.builder()
                    .transaction(transaction)
                    .receiverAccount(receiverAccountFor(transaction, itemType))
                    .itemType(itemType)
                    .grossAmount(amounts.grossAmount())
                    .feeAmount(amounts.feeAmount())
                    .netAmount(amounts.netAmount())
                    .platformRevenue(amounts.feeAmount())
                    .receiverPayable(amounts.netAmount())
                    .currency(transaction.getCurrency())
                    .payoutStatus(PayoutStatus.PENDING)
                    .build();
            batch.addItem(item);

            grossTotal = grossTotal.add(amounts.grossAmount());
            feeTotal = feeTotal.add(amounts.feeAmount());
            netTotal = netTotal.add(amounts.netAmount());
        }

        batch.setGrossAmount(grossTotal);
        batch.setFeeAmount(feeTotal);
        batch.setNetAmount(netTotal);

        SettlementBatch saved = settlementBatchRepository.save(batch);
        recordSettlementLedger(saved);
        return SettlementBatchResponse.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public SettlementBatchResponse getBatch(UUID batchId, String initiatorEmail) {
        assertPrivileged(initiatorEmail);
        SettlementBatch batch = settlementBatchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("SettlementBatch", batchId));
        return SettlementBatchResponse.from(batch);
    }

    private BigDecimal calculateFee(BigDecimal grossAmount, int feeRateBps) {
        return BasisPointRate.of(feeRateBps).applyTo(grossAmount);
    }

    private SettlementItemType itemTypeFor(Transaction transaction) {
        if (transaction.getOriginalTransaction() == null) {
            return SettlementItemType.NORMAL;
        }
        return SettlementItemType.ADJUSTMENT;
    }

    private SettlementAmounts amountsFor(
            Transaction transaction,
            int feeRateBps,
            SettlementItemType itemType
    ) {
        BigDecimal grossAmount = transaction.getAmount();
        BigDecimal feeAmount = itemType == SettlementItemType.ADJUSTMENT
                ? calculateAdjustmentFee(transaction, grossAmount)
                : calculateFee(grossAmount, feeRateBps);
        return new SettlementAmounts(grossAmount, feeAmount, grossAmount.subtract(feeAmount));
    }

    private BigDecimal calculateAdjustmentFee(Transaction transaction, BigDecimal grossAmount) {
        SettlementItem originalItem = originalSettlementItem(transaction);
        BigDecimal originalAmount = transaction.getOriginalTransaction().getAmount();
        return originalItem.getFeeAmount()
                .multiply(grossAmount)
                .divide(originalAmount, 2, RoundingMode.HALF_UP);
    }

    private Account receiverAccountFor(Transaction transaction, SettlementItemType itemType) {
        if (itemType == SettlementItemType.NORMAL) {
            return transaction.getToAccount();
        }
        return originalSettlementItem(transaction).getReceiverAccount();
    }

    private SettlementItem originalSettlementItem(Transaction transaction) {
        Transaction original = transaction.getOriginalTransaction();
        if (original == null) {
            throw new IllegalArgumentException("Settlement adjustment requires an original transaction");
        }
        return settlementItemRepository.findByTransaction_Id(original.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Original transaction has no settlement item: " + original.getId()
                ));
    }

    private boolean matchesRequest(
            SettlementBatch batch,
            String currency,
            int feeRateBps,
            OffsetDateTime cutoffCompletedAt
    ) {
        return batch.getCurrency().equals(currency)
                && batch.getFeeRateBps() == feeRateBps
                && batch.getCutoffCompletedAt().isEqual(cutoffCompletedAt);
    }

    private void recordSettlementLedger(SettlementBatch batch) {
        Account clearingAccount = settlementAccount(SettlementAccountRole.CLEARING, batch.getCurrency());
        Account receiverPayableAccount = settlementAccount(SettlementAccountRole.RECEIVER_PAYABLE, batch.getCurrency());
        Account platformRevenueAccount = settlementAccount(SettlementAccountRole.PLATFORM_REVENUE, batch.getCurrency());

        for (SettlementItem item : batch.getItems()) {
            if (item.getItemType() == SettlementItemType.ADJUSTMENT) {
                ledgerService.recordSettlementAdjustment(
                        item.getTransaction(),
                        clearingAccount,
                        receiverPayableAccount,
                        platformRevenueAccount,
                        item.getGrossAmount(),
                        item.getReceiverPayable(),
                        item.getPlatformRevenue()
                );
            } else {
                ledgerService.recordSettlement(
                        item.getTransaction(),
                        clearingAccount,
                        receiverPayableAccount,
                        platformRevenueAccount,
                        item.getGrossAmount(),
                        item.getReceiverPayable(),
                        item.getPlatformRevenue()
                );
            }
        }
    }

    private Account settlementAccount(SettlementAccountRole role, String currency) {
        String accountNumber = role.accountNumber(currency);
        return accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("SettlementAccount", accountNumber));
    }

    private void assertPrivileged(String initiatorEmail) {
        User initiator = userRepository.findByEmail(initiatorEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User", initiatorEmail));
        if (initiator.getRole() != Role.ADMIN && initiator.getRole() != Role.OPERATOR) {
            throw new AccessDeniedException("Caller is not authorized for settlement operations");
        }
    }

    private enum SettlementAccountRole {
        CLEARING("CLEARING"),
        RECEIVER_PAYABLE("PAYABLE"),
        PLATFORM_REVENUE("REVENUE");

        private final String accountNumberPrefix;

        SettlementAccountRole(String accountNumberPrefix) {
            this.accountNumberPrefix = accountNumberPrefix;
        }

        private String accountNumber(String currency) {
            return accountNumberPrefix + "-" + currency;
        }
    }

    private record BasisPointRate(int value) {
        private static final BigDecimal DENOMINATOR = new BigDecimal("10000");

        private static BasisPointRate of(int value) {
            return new BasisPointRate(value);
        }

        private BigDecimal applyTo(BigDecimal amount) {
            return amount
                    .multiply(BigDecimal.valueOf(value))
                    .divide(DENOMINATOR, 2, RoundingMode.HALF_UP);
        }
    }

    private record SettlementAmounts(
            BigDecimal grossAmount,
            BigDecimal feeAmount,
            BigDecimal netAmount
    ) {
    }
}
