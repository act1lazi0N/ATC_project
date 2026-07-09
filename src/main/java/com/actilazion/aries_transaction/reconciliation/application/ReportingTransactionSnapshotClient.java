package com.actilazion.aries_transaction.reconciliation.application;

import com.actilazion.aries_transaction.reconciliation.dto.ReportingTransactionSnapshot;

import java.time.OffsetDateTime;
import java.util.List;

public interface ReportingTransactionSnapshotClient {
    List<ReportingTransactionSnapshot> fetchSnapshots(String currency, OffsetDateTime windowStart, OffsetDateTime windowEnd);
}
