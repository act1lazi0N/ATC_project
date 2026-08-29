package com.actilazion.aries_transaction.transaction.application;

import com.actilazion.aries_transaction.transaction.infrastructure.TransferPreviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "app.transfer.preview",
        name = "cleanup-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class TransferPreviewCleanupJob {
    private final TransferPreviewRepository previewRepository;
    private final TransferPreviewProperties properties;

    @Scheduled(fixedDelayString = "${app.transfer.preview.cleanup-fixed-delay-ms:3600000}")
    @Transactional
    public int purgeExpiredPreviews() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusHours(properties.getRetentionHours());
        int deleted = previewRepository.deleteExpiredBefore(cutoff);
        if (deleted > 0) {
            log.info("[TRANSFER_PREVIEW] Purged expired previews count={}", deleted);
        }
        return deleted;
    }
}
