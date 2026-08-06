package com.actilazion.aries_transaction.reconciliation.infrastructure;

import com.actilazion.aries_transaction.reconciliation.application.ReportingTransactionSnapshotClient;
import com.actilazion.aries_transaction.reconciliation.domain.exception.ReportingSnapshotClientUnavailableException;
import com.actilazion.aries_transaction.reconciliation.dto.ReportingTransactionSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

@Component
@ConditionalOnProperty(
        prefix = "app.reconciliation.reporting",
        name = "mode",
        havingValue = "disabled",
        matchIfMissing = true
)
public class DisabledReportingTransactionSnapshotClient implements ReportingTransactionSnapshotClient {
    @Override
    public List<ReportingTransactionSnapshot> fetchSnapshots(
            String currency,
            OffsetDateTime windowStart,
            OffsetDateTime windowEnd
    ) {
        throw new ReportingSnapshotClientUnavailableException(
                "Reporting snapshot client is disabled; set app.reconciliation.reporting.mode=http"
        );
    }
}
