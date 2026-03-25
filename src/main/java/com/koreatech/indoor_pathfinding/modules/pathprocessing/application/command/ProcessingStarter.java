package com.koreatech.indoor_pathfinding.modules.pathprocessing.application.command;

import com.koreatech.indoor_pathfinding.modules.pathprocessing.application.dto.response.ProcessingStartResponse;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.application.dto.response.ProcessingStatusResponse;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.infrastructure.external.PathProcessingClient;
import com.koreatech.indoor_pathfinding.modules.scan.application.query.MergedScanReader;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.MergedScan;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.MergedScanStatus;
import com.koreatech.indoor_pathfinding.modules.scan.domain.repository.MergedScanRepository;
import com.koreatech.indoor_pathfinding.shared.exception.BusinessException;
import com.koreatech.indoor_pathfinding.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessingStarter {

    private final PathProcessingClient pathProcessingClient;
    private final MergedScanReader mergedScanReader;
    private final MergedScanRepository mergedScanRepository;
    private final ProcessingResultApplier processingResultApplier;

    private static final Map<UUID, String> floorToJobMap = new ConcurrentHashMap<>();
    private static final Map<UUID, String> floorToPythonFileId = new ConcurrentHashMap<>();

    public ProcessingStartResponse start(final UUID floorId) {
        final MergedScan mergedScan = mergedScanReader.findEntityByFloorId(floorId);

        if (!mergedScan.isProcessable()) {
            throw new BusinessException(ErrorCode.MERGED_SCAN_NOT_PROCESSABLE,
                "Current status: " + mergedScan.getStatus());
        }

        final String fileId = pathProcessingClient.uploadFile(Paths.get(mergedScan.getFilePath()));
        floorToPythonFileId.put(floorId, fileId);

        final String jobId = pathProcessingClient.startProcessing(fileId);
        mergedScan.startProcessing();
        mergedScanRepository.save(mergedScan);

        floorToJobMap.put(floorId, jobId);

        CompletableFuture.runAsync(() -> processInBackground(floorId, jobId, fileId));

        return new ProcessingStartResponse(jobId, floorId);
    }

    private void processInBackground(final UUID floorId, final String jobId, final String pythonFileId) {
        try {
            if (!waitForCompletion(jobId)) {
                updateMergedScanStatus(floorId, "Processing job failed or timed out");
                return;
            }

            log.info("Applying results for floor {}", floorId);
            processingResultApplier.applyToFloor(floorId, jobId);

            final String plyKey = extractPly(floorId, pythonFileId);
            if (plyKey != null) {
                processingResultApplier.updateFloorPly(floorId, plyKey);
            }

            log.info("Floor {} processing completed", floorId);

        } catch (Exception e) {
            log.error("Processing failed for floor {}: {}", floorId, e.getMessage(), e);
            updateMergedScanStatus(floorId, e.getMessage());
        }
    }

    private boolean waitForCompletion(final String jobId) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            Thread.sleep(10_000);
            final ProcessingStatusResponse status = pathProcessingClient.getJobStatus(jobId);
            log.debug("Job {} status: {} ({}%)", jobId, status.status(), status.progress());

            if ("COMPLETED".equals(status.status())) return true;
            if ("FAILED".equals(status.status())) {
                log.error("Job {} failed: {}", jobId, status.error());
                return false;
            }
        }
        log.warn("Job {} timeout", jobId);
        return false;
    }

    private String extractPly(final UUID floorId, final String pythonFileId) {
        try {
            log.info("Extracting PLY for floor {}", floorId);
            return pathProcessingClient.extractPointcloudPly(pythonFileId);
        } catch (Exception e) {
            log.warn("PLY extraction failed for floor {}: {}", floorId, e.getMessage());
            return null;
        }
    }

    private void updateMergedScanStatus(final UUID floorId, final String errorMessage) {
        try {
            final MergedScan mergedScan = mergedScanReader.findEntityByFloorId(floorId);
            mergedScan.markFailed(errorMessage);
            mergedScanRepository.save(mergedScan);
        } catch (Exception e) {
            log.error("Failed to update merged scan status for floor {}", floorId, e);
        }
    }

    public static String getJobIdForFloor(final UUID floorId) {
        return floorToJobMap.get(floorId);
    }

    public static String getPythonFileIdForFloor(final UUID floorId) {
        return floorToPythonFileId.get(floorId);
    }
}
