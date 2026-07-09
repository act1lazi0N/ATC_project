package com.actilazion.aries_transaction.reconciliation.infrastructure;

import com.actilazion.aries_transaction.reconciliation.application.ReportingTransactionSnapshotClient;
import com.actilazion.aries_transaction.reconciliation.dto.ReportingTransactionSnapshot;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

@Component
public class NoopReportingTransactionSnapshotClient implements ReportingTransactionSnapshotClient {
    @Override
    public List<ReportingTransactionSnapshot> fetchSnapshots(String currency, OffsetDateTime windowStart, OffsetDateTime windowEnd) {
        return List.of();
    }
}
