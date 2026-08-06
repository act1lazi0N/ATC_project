package com.actilazion.aries_transaction.reconciliation;

import com.actilazion.aries_transaction.reconciliation.application.ReportingClientProperties;
import com.actilazion.aries_transaction.reconciliation.infrastructure.HttpReportingTransactionSnapshotClient;
import com.actilazion.aries_transaction.transaction.domain.TransactionStatus;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HttpReportingTransactionSnapshotClientTest {
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fetchSnapshots_callsConfiguredEndpointAndMapsResponse() throws IOException {
        UUID transactionId = UUID.randomUUID();
        AtomicReference<String> query = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/snapshots", exchange -> {
            query.set(exchange.getRequestURI().getQuery());
            byte[] response = """
                    [{
                      "transactionId": "%s",
                      "amount": 123.45,
                      "currency": "VND",
                      "status": "COMPLETED",
                      "completedAt": "2026-07-01T00:00:00Z"
                    }]
                    """.formatted(transactionId).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        ReportingClientProperties properties = new ReportingClientProperties();
        properties.setMode(ReportingClientProperties.ReportingClientMode.HTTP);
        properties.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        properties.setSnapshotsPath("/snapshots");
        HttpReportingTransactionSnapshotClient client = new HttpReportingTransactionSnapshotClient(
                RestClient.builder(),
                properties
        );

        var snapshots = client.fetchSnapshots(
                "VND",
                OffsetDateTime.parse("2026-07-01T00:00:00Z"),
                OffsetDateTime.parse("2026-07-02T00:00:00Z")
        );

        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.getFirst().transactionId()).isEqualTo(transactionId);
        assertThat(snapshots.getFirst().amount()).isEqualByComparingTo("123.45");
        assertThat(snapshots.getFirst().currency()).isEqualTo("VND");
        assertThat(snapshots.getFirst().status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(query.get()).contains("currency=VND");
    }
}
