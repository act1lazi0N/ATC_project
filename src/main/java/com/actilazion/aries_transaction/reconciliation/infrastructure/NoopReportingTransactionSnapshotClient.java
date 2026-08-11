package com.actilazion.aries_transaction.reconciliation.infrastructure;

import com.actilazion.aries_transaction.reconciliation.application.ReportingTransactionSnapshotClient;
import com.actilazion.aries_transaction.reconciliation.dto.ReportingTransactionSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "app.reconciliation.reporting", name = "mode", havingValue = "noop")
public class NoopReportingTransactionSnapshotClient implements ReportingTransactionSnapshotClient {
    @Override
    public List<ReportingTransactionSnapshot> fetchSnapshots(String currency, OffsetDateTime windowStart, OffsetDateTime windowEnd) {
        return List.of();
    }
}
