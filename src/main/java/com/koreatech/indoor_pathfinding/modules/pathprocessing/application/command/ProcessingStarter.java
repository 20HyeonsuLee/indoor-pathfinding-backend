package com.koreatech.indoor_pathfinding.modules.pathprocessing.application.command;

import com.koreatech.indoor_pathfinding.modules.pathprocessing.application.dto.response.ProcessingStartResponse;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.application.dto.response.ProcessingStatusResponse;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.infrastructure.external.PathProcessingClient;
import com.koreatech.indoor_pathfinding.modules.scan.application.command.ScanStatusUpdater;
import com.koreatech.indoor_pathfinding.modules.scan.application.query.ScanSessionReader;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ScanSession;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ScanStatus;
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
    private final ScanSessionReader scanSessionReader;
    private final ScanStatusUpdater scanStatusUpdater;
    private final ProcessingResultApplier processingResultApplier;

    private static final Map<String, UUID> jobToSessionMap = new ConcurrentHashMap<>();
    private static final Map<UUID, String> sessionToJobMap = new ConcurrentHashMap<>();
    private static final Map<UUID, String> sessionToPythonFileId = new ConcurrentHashMap<>();

    public ProcessingStartResponse start(UUID buildingId, UUID sessionId) {
        ScanSession session = scanSessionReader.findEntityById(sessionId);

        if (!canStartProcessing(session.getStatus())) {
            throw new BusinessException(ErrorCode.INVALID_BUILDING_STATUS,
                "Scan session cannot be processed in current state: " + session.getStatus());
        }

        String fileId = pathProcessingClient.uploadFile(Paths.get(session.getFilePath()));
        sessionToPythonFileId.put(sessionId, fileId);

        String jobId = pathProcessingClient.startProcessing(fileId);
        scanStatusUpdater.updateStatus(sessionId, ScanStatus.EXTRACTING);

        jobToSessionMap.put(jobId, sessionId);
        sessionToJobMap.put(sessionId, jobId);

        // 비동기: 처리 완료 대기 → 층 생성 → PLY 추출
        CompletableFuture.runAsync(() -> processInBackground(sessionId, jobId, fileId));

        return new ProcessingStartResponse(jobId, sessionId);
    }

    private void processInBackground(UUID sessionId, String jobId, String pythonFileId) {
        try {
            // 1. Python 처리 완료 대기 (최대 10분)
            if (!waitForCompletion(jobId)) {
                scanStatusUpdater.updateStatus(sessionId, ScanStatus.FAILED);
                return;
            }

            // 2. 결과 적용 → 층 + 수직통로 자동 생성
            log.info("Applying results for session {}", sessionId);
            processingResultApplier.apply(sessionId);

            // 3. 전체 PLY 추출 (file_id 기반 — 로컬/Docker 경로 무관)
            String wholePlyKey = extractWholePly(sessionId, pythonFileId);

            // 4. 층별 PLY 추출 (@Transactional 서비스 내에서 실행 → lazy 로딩 가능)
            if (wholePlyKey != null) {
                ScanSession session = scanSessionReader.findEntityById(sessionId);
                processingResultApplier.extractFloorPlys(session.getBuilding().getId(), wholePlyKey);
            }

            scanStatusUpdater.updateStatus(sessionId, ScanStatus.COMPLETED);
            log.info("Session {} fully completed", sessionId);

        } catch (Exception e) {
            log.error("Processing failed for session {}: {}", sessionId, e.getMessage(), e);
            scanStatusUpdater.updateStatus(sessionId, ScanStatus.FAILED);
        }
    }

    private boolean waitForCompletion(String jobId) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            Thread.sleep(10_000);
            ProcessingStatusResponse status = pathProcessingClient.getJobStatus(jobId);
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

    private String extractWholePly(UUID sessionId, String pythonFileId) {
        try {
            log.info("Extracting whole PLY for fileId: {}", pythonFileId);
            String cacheKey = pathProcessingClient.extractPointcloudPly(pythonFileId);
            scanStatusUpdater.updatePlyFileId(sessionId, cacheKey);
            return cacheKey;
        } catch (Exception e) {
            log.warn("Whole PLY extraction failed: {}", e.getMessage());
            return null;
        }
    }

    public static String getJobIdForSession(UUID sessionId) {
        return sessionToJobMap.get(sessionId);
    }

    public static String getPythonFileIdForSession(UUID sessionId) {
        return sessionToPythonFileId.get(sessionId);
    }

    private boolean canStartProcessing(ScanStatus status) {
        return status != ScanStatus.EXTRACTING && status != ScanStatus.PROCESSING;
    }
}
