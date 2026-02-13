package com.koreatech.indoor_pathfinding.modules.localization.application.event;

import com.koreatech.indoor_pathfinding.modules.localization.infrastructure.external.VpsClient;
import com.koreatech.indoor_pathfinding.modules.scan.domain.event.ScanFileUploadedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScanFileUploadedEventListener {

    private final VpsClient vpsClient;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleScanFileUploaded(final ScanFileUploadedEvent event) {
        log.info("Scan file uploaded for building: {}, triggering VPS SLAM processing", event.buildingId());

        try {
            vpsClient.processSlam(event.buildingId().toString());
        } catch (Exception exception) {
            log.warn("VPS SLAM processing failed for building: {} - {}", event.buildingId(), exception.getMessage());
        }
    }
}
