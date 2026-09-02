package com.actilazion.aries_transaction.overview.application;

import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.common.exception.ResourceNotFoundException;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.overview.dto.MerchantCurrencyOverviewResponse;
import com.actilazion.aries_transaction.overview.dto.MerchantOverviewResponse;
import com.actilazion.aries_transaction.overview.dto.MerchantTrendPointResponse;
import com.actilazion.aries_transaction.settlement.infrastructure.SettlementItemRepository;
import com.actilazion.aries_transaction.transaction.domain.Transaction;
import com.actilazion.aries_transaction.transaction.domain.TransactionOperation;
import com.actilazion.aries_transaction.transaction.domain.TransactionStatus;
import com.actilazion.aries_transaction.transaction.infrastructure.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MerchantOverviewService {
    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final SettlementItemRepository settlementItemRepository;

    @Transactional(readOnly = true)
    public MerchantOverviewResponse getOverview(UUID userId, String range, String timezone) {
        User merchant = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        if (merchant.getRole() != Role.MERCHANT) {
            throw new AccessDeniedException("Merchant overview is available only to merchants");
        }
        int days = switch (range) {
            case "7d" -> 7;
            case "30d" -> 30;
            case "90d" -> 90;
            default -> throw new IllegalArgumentException("range must be 7d, 30d, or 90d");
        };
        ZoneId zone = parseZone(timezone);
        OffsetDateTime now = OffsetDateTime.now(zone);
        OffsetDateTime from = now.toLocalDate().minusDays(days - 1L).atStartOfDay(zone).toOffsetDateTime();

        Map<String, CurrencyAccumulator> currencies = new LinkedHashMap<>();
        for (Account account : accountRepository.findAllByUserId(userId)) {
            accumulator(currencies, account.getCurrency()).balance = accumulator(currencies, account.getCurrency())
                    .balance.add(account.getBalance());
        }
        for (Transaction transaction : transactionRepository.findDashboardTransactions(userId, from, now)) {
            CurrencyAccumulator bucket = accumulator(currencies, transaction.getCurrency());
            boolean fromMerchant = transaction.getFromAccount().getUser().getId().equals(userId);
            boolean toMerchant = transaction.getToAccount().getUser().getId().equals(userId);
            LocalDate day = transaction.getCreatedAt().atZoneSameInstant(zone).toLocalDate();
            DailyAccumulator daily = bucket.trend.computeIfAbsent(day, ignored -> new DailyAccumulator());
            if (transaction.getStatus() == TransactionStatus.PENDING) {
                bucket.pending = bucket.pending.add(transaction.getAmount());
                bucket.pendingCount++;
                continue;
            }
            if (!isConfirmed(transaction.getStatus())) continue;
            if (toMerchant) {
                bucket.inflow = bucket.inflow.add(transaction.getAmount());
                daily.inflow = daily.inflow.add(transaction.getAmount());
            }
            if (fromMerchant) {
                bucket.outflow = bucket.outflow.add(transaction.getAmount());
                daily.outflow = daily.outflow.add(transaction.getAmount());
                if (transaction.getOperation() == TransactionOperation.REFUND) {
                    bucket.refunds = bucket.refunds.add(transaction.getAmount());
                }
            }
        }
        settlementItemRepository.findAllByReceiverAccount_User_IdAndCreatedAtGreaterThanEqual(userId, from)
                .forEach(item -> accumulator(currencies, item.getCurrency()).settlementNet =
                        accumulator(currencies, item.getCurrency()).settlementNet.add(item.getNetAmount()));

        List<MerchantCurrencyOverviewResponse> result = currencies.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getValue().response(entry.getKey(), from.toLocalDate(), now.toLocalDate()))
                .toList();
        return new MerchantOverviewResponse(range, zone.getId(), now, result);
    }

    private boolean isConfirmed(TransactionStatus status) {
        return status == TransactionStatus.COMPLETED
                || status == TransactionStatus.PARTIALLY_REFUNDED
                || status == TransactionStatus.REFUNDED;
    }

    private ZoneId parseZone(String timezone) {
        try {
            return ZoneId.of(timezone == null || timezone.isBlank() ? "UTC" : timezone);
        } catch (ZoneRulesException exception) {
            throw new IllegalArgumentException("Invalid timezone");
        }
    }

    private CurrencyAccumulator accumulator(Map<String, CurrencyAccumulator> currencies, String currency) {
        return currencies.computeIfAbsent(currency.toUpperCase(Locale.ROOT), ignored -> new CurrencyAccumulator());
    }

    private static final class CurrencyAccumulator {
        private BigDecimal balance = BigDecimal.ZERO;
        private BigDecimal inflow = BigDecimal.ZERO;
        private BigDecimal outflow = BigDecimal.ZERO;
        private BigDecimal refunds = BigDecimal.ZERO;
        private BigDecimal pending = BigDecimal.ZERO;
        private long pendingCount;
        private BigDecimal settlementNet = BigDecimal.ZERO;
        private final Map<LocalDate, DailyAccumulator> trend = new LinkedHashMap<>();

        private MerchantCurrencyOverviewResponse response(String currency, LocalDate from, LocalDate to) {
            List<MerchantTrendPointResponse> points = new ArrayList<>();
            for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
                DailyAccumulator value = trend.getOrDefault(day, new DailyAccumulator());
                points.add(new MerchantTrendPointResponse(day, value.inflow.toPlainString(), value.outflow.toPlainString()));
            }
            return new MerchantCurrencyOverviewResponse(
                    currency,
                    balance.toPlainString(),
                    inflow.toPlainString(),
                    outflow.toPlainString(),
                    refunds.toPlainString(),
                    pending.toPlainString(),
                    pendingCount,
                    settlementNet.toPlainString(),
                    points
            );
        }
    }

    private static final class DailyAccumulator {
        private BigDecimal inflow = BigDecimal.ZERO;
        private BigDecimal outflow = BigDecimal.ZERO;
    }
}
