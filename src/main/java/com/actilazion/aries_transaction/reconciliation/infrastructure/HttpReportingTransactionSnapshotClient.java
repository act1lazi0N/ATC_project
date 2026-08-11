package com.actilazion.aries_transaction.reconciliation.infrastructure;

import com.actilazion.aries_transaction.reconciliation.application.ReportingClientProperties;
import com.actilazion.aries_transaction.reconciliation.application.ReportingTransactionSnapshotClient;
import com.actilazion.aries_transaction.reconciliation.domain.exception.ReportingSnapshotClientUnavailableException;
import com.actilazion.aries_transaction.reconciliation.dto.ReportingTransactionSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.OffsetDateTime;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "app.reconciliation.reporting", name = "mode", havingValue = "http")
public class HttpReportingTransactionSnapshotClient implements ReportingTransactionSnapshotClient {
    private static final ParameterizedTypeReference<List<ReportingTransactionSnapshot>> SNAPSHOT_LIST =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final ReportingClientProperties properties;

    public HttpReportingTransactionSnapshotClient(
            RestClient.Builder restClientBuilder,
            ReportingClientProperties properties
    ) {
        this.restClient = restClientBuilder
                .baseUrl(properties.getBaseUrl())
                .build();
        this.properties = properties;
    }

    @Override
    public List<ReportingTransactionSnapshot> fetchSnapshots(
            String currency,
            OffsetDateTime windowStart,
            OffsetDateTime windowEnd
    ) {
        try {
            List<ReportingTransactionSnapshot> snapshots = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(properties.getSnapshotsPath())
                            .queryParam("currency", currency)
                            .queryParam("windowStart", windowStart)
                            .queryParam("windowEnd", windowEnd)
                            .build())
                    .retrieve()
                    .body(SNAPSHOT_LIST);
            return snapshots != null ? snapshots : List.of();
        } catch (RestClientException ex) {
            throw new ReportingSnapshotClientUnavailableException("Failed to fetch reporting snapshots", ex);
        }
    }
}
