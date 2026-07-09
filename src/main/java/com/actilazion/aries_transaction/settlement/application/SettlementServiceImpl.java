package com.actilazion.aries_transaction.settlement.application;

import com.actilazion.aries_transaction.common.exception.ResourceNotFoundException;
import com.actilazion.aries_transaction.settlement.domain.PayoutStatus;
import com.actilazion.aries_transaction.settlement.domain.SettlementBatch;
import com.actilazion.aries_transaction.settlement.domain.SettlementBatchStatus;
import com.actilazion.aries_transaction.settlement.domain.SettlementItem;
import com.actilazion.aries_transaction.settlement.dto.SettlementBatchResponse;
import com.actilazion.aries_transaction.settlement.exception.NoSettlementCandidateException;
import com.actilazion.aries_transaction.settlement.persistence.SettlementBatchRepository;
import com.actilazion.aries_transaction.transaction.domain.Transaction;
import com.actilazion.aries_transaction.transaction.persistence.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SettlementServiceImpl implements SettlementService {
    private static final BigDecimal BASIS_POINTS = new BigDecimal("10000");

    private final TransactionRepository transactionRepository;
    private final SettlementBatchRepository settlementBatchRepository;

    @Override
    @Transactional
    public SettlementBatchResponse createBatch(String currency, int feeRateBps) {
        List<Transaction> candidates = transactionRepository.findSettlementCandidates(currency);
        if (candidates.isEmpty()) {
            throw new NoSettlementCandidateException(currency);
        }

        SettlementBatch batch = SettlementBatch.builder()
                .currency(currency)
                .grossAmount(BigDecimal.ZERO)
                .feeAmount(BigDecimal.ZERO)
                .netAmount(BigDecimal.ZERO)
                .feeRateBps(feeRateBps)
                .status(SettlementBatchStatus.OPEN)
                .build();

        BigDecimal grossTotal = BigDecimal.ZERO;
        BigDecimal feeTotal = BigDecimal.ZERO;
        BigDecimal netTotal = BigDecimal.ZERO;

        for (Transaction transaction : candidates) {
            BigDecimal grossAmount = transaction.getAmount();
            BigDecimal feeAmount = calculateFee(grossAmount, feeRateBps);
            BigDecimal netAmount = grossAmount.subtract(feeAmount);

            SettlementItem item = SettlementItem.builder()
                    .transaction(transaction)
                    .receiverAccount(transaction.getToAccount())
                    .grossAmount(grossAmount)
                    .feeAmount(feeAmount)
                    .netAmount(netAmount)
                    .platformRevenue(feeAmount)
                    .receiverPayable(netAmount)
                    .currency(transaction.getCurrency())
                    .payoutStatus(PayoutStatus.PENDING)
                    .build();
            batch.addItem(item);

            grossTotal = grossTotal.add(grossAmount);
            feeTotal = feeTotal.add(feeAmount);
            netTotal = netTotal.add(netAmount);
        }

        batch.setGrossAmount(grossTotal);
        batch.setFeeAmount(feeTotal);
        batch.setNetAmount(netTotal);

        SettlementBatch saved = settlementBatchRepository.save(batch);
        return SettlementBatchResponse.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public SettlementBatchResponse getBatch(UUID batchId) {
        SettlementBatch batch = settlementBatchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("SettlementBatch", batchId));
        return SettlementBatchResponse.from(batch);
    }

    private BigDecimal calculateFee(BigDecimal grossAmount, int feeRateBps) {
        return grossAmount
                .multiply(BigDecimal.valueOf(feeRateBps))
                .divide(BASIS_POINTS, 2, RoundingMode.HALF_UP);
    }
}
