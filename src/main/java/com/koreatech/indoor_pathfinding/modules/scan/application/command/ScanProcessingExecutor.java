package com.koreatech.indoor_pathfinding.modules.scan.application.command;

import com.koreatech.indoor_pathfinding.modules.pathprocessing.application.command.ProcessingResultApplier;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.application.dto.response.ProcessingStatusResponse;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.infrastructure.external.PathProcessingClient;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.MergedScan;
import com.koreatech.indoor_pathfinding.modules.scan.domain.repository.MergedScanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScanProcessingExecutor {

    private final PathProcessingClient pathProcessingClient;
    private final ProcessingResultApplier processingResultApplier;
    private final MergedScanRepository mergedScanRepository;

    /**
     * 병합 완료 후 자동으로 궤적 추출 + PLY 생성.
     * 별도 스레드에서 자체 트랜잭션으로 실행된다.
     */
    @Async
    @Transactional
    public void processAndExtractPly(final UUID mergedScanId, final UUID floorId) {
        try {
            final MergedScan mergedScan = mergedScanRepository.findById(mergedScanId).orElse(null);
            if (mergedScan == null) {
                log.warn("MergedScan not found: {}", mergedScanId);
                return;
            }

            log.info("Starting processing + PLY extraction (floor={}, mergedScan={})", floorId, mergedScanId);

            // 1. Python에 .db 업로드 + 처리 시작
            final String fileId = pathProcessingClient.uploadFile(Paths.get(mergedScan.getFilePath()));
            final String jobId = pathProcessingClient.startProcessing(fileId);

            mergedScan.startProcessing();
            mergedScanRepository.save(mergedScan);

            // 2. 처리 완료 대기
            if (!waitForProcessing(jobId)) {
                mergedScan.markFailed("Processing failed or timed out");
                mergedScanRepository.save(mergedScan);
                return;
            }

            // 3. 결과 적용 (FloorPath 갱신)
            processingResultApplier.applyToFloor(floorId, jobId);

            // 4. PLY 추출 (기존 PLY 덮어쓰기)
            final String plyKey = pathProcessingClient.extractPointcloudPly(fileId);
            processingResultApplier.updateFloorPly(floorId, plyKey);

            log.info("Floor {} processing + PLY extraction completed (ply={})", floorId, plyKey);

        } catch (Exception e) {
            log.error("Processing/PLY extraction failed for floor {}: {}", floorId, e.getMessage(), e);
            updateFailed(mergedScanId, e.getMessage());
        }
    }

    /**
     * 다중 청크 rtabmap-reprocess 병합 → 성공 시 자동으로 처리 + PLY 추출.
     */
    @Async
    @Transactional
    public void mergeAndProcess(final UUID mergedScanId, final UUID floorId,
                                final List<String> chunkPaths, final String outputPath) {
        try {
            final String mergeJobId = pathProcessingClient.mergeChunks(chunkPaths, outputPath);

            for (int i = 0; i < 60; i++) {
                Thread.sleep(30_000);
                final Map<String, Object> status = pathProcessingClient.getMergeStatus(mergeJobId);
                final String statusStr = (String) status.get("status");

                if ("COMPLETED".equals(statusStr)) {
                    final MergedScan mergedScan = mergedScanRepository.findById(mergedScanId).orElse(null);
                    if (mergedScan != null) {
                        mergedScan.markMerged(outputPath);
                        mergedScanRepository.save(mergedScan);
                    }
                    log.info("Merge completed, starting processing for floor {}", floorId);
                    processAndExtractPly(mergedScanId, floorId);
                    return;
                }

                if ("FAILED".equals(statusStr)) {
                    updateFailed(mergedScanId, (String) status.get("error"));
                    return;
                }
            }

            updateFailed(mergedScanId, "Merge timeout (30 minutes)");

        } catch (Exception e) {
            log.error("Merge failed for floor {}: {}", floorId, e.getMessage(), e);
            updateFailed(mergedScanId, e.getMessage());
        }
    }

    private boolean waitForProcessing(final String jobId) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            Thread.sleep(10_000);
            final ProcessingStatusResponse status = pathProcessingClient.getJobStatus(jobId);

            if ("COMPLETED".equals(status.status())) return true;
            if ("FAILED".equals(status.status())) {
                log.error("Processing job {} failed: {}", jobId, status.error());
                return false;
            }
        }
        log.warn("Processing job {} timeout", jobId);
        return false;
    }

    private void updateFailed(final UUID mergedScanId, final String errorMessage) {
        final MergedScan mergedScan = mergedScanRepository.findById(mergedScanId).orElse(null);
        if (mergedScan != null) {
            mergedScan.markFailed(errorMessage);
            mergedScanRepository.save(mergedScan);
        }
    }
}
