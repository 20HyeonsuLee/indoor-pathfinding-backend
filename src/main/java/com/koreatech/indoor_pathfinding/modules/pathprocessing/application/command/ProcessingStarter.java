package com.koreatech.indoor_pathfinding.modules.pathprocessing.application.command;

import com.koreatech.indoor_pathfinding.modules.pathprocessing.application.dto.response.ProcessingStartResponse;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.infrastructure.external.PathProcessingClient;
import com.koreatech.indoor_pathfinding.modules.scan.application.command.ScanStatusUpdater;
import com.koreatech.indoor_pathfinding.modules.scan.application.query.ScanSessionReader;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ScanSession;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ScanStatus;
import com.koreatech.indoor_pathfinding.shared.exception.BusinessException;
import com.koreatech.indoor_pathfinding.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessingStarter {

    private final PathProcessingClient pathProcessingClient;
    private final ScanSessionReader scanSessionReader;
    private final ScanStatusUpdater scanStatusUpdater;

    private static final Map<String, UUID> jobToSessionMap = new ConcurrentHashMap<>();
    private static final Map<UUID, String> sessionToJobMap = new ConcurrentHashMap<>();
    // uploadFile()이 반환한 fileId → Python 내부 경로 매핑
    private static final Map<UUID, String> sessionToPythonFileId = new ConcurrentHashMap<>();

    @Transactional
    public ProcessingStartResponse start(UUID buildingId, UUID sessionId) {
        ScanSession session = scanSessionReader.findEntityById(sessionId);

        if (!canStartProcessing(session.getStatus())) {
            throw new BusinessException(ErrorCode.INVALID_BUILDING_STATUS,
                "Scan session cannot be processed in current state: " + session.getStatus());
        }

        // 1. Python 서비스에 파일 업로드 → Python이 자체 UUID로 저장
        String fileId = pathProcessingClient.uploadFile(Paths.get(session.getFilePath()));
        sessionToPythonFileId.put(sessionId, fileId);

        // 2. PLY 추출 비동기 시작 (Python이 저장한 파일 경로 사용)
        extractPlyAsync(sessionId, fileId);

        // 3. 처리 시작 (층 감지 — 그래프 자동 생성은 ResultApplier에서 건너뜀)
        String jobId = pathProcessingClient.startProcessing(fileId);

        scanStatusUpdater.updateStatus(sessionId, ScanStatus.EXTRACTING);

        jobToSessionMap.put(jobId, sessionId);
        sessionToJobMap.put(sessionId, jobId);

        return new ProcessingStartResponse(jobId, sessionId);
    }

    @Async
    public void extractPlyAsync(UUID sessionId, String pythonFileId) {
        try {
            // Python 컨테이너 내부 경로: /app/uploads/{pythonFileId}.db
            String pythonPath = "/app/uploads/" + pythonFileId + ".db";

            log.info("PLY extraction started: session={}, pythonPath={}", sessionId, pythonPath);
            String cacheKey = pathProcessingClient.extractPointcloudPly(pythonPath);
            scanStatusUpdater.updatePlyFileId(sessionId, cacheKey);
        } catch (Exception e) {
            log.warn("PLY extraction failed (will retry on demand): {}", e.getMessage());
        }
    }

    public static String getJobIdForSession(UUID sessionId) {
        return sessionToJobMap.get(sessionId);
    }

    public static UUID getSessionIdForJob(String jobId) {
        return jobToSessionMap.get(jobId);
    }

    public static String getPythonFileIdForSession(UUID sessionId) {
        return sessionToPythonFileId.get(sessionId);
    }

    private boolean canStartProcessing(ScanStatus status) {
        return status != ScanStatus.EXTRACTING && status != ScanStatus.PROCESSING;
    }
}
