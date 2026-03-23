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
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessingStarter {

    private final PathProcessingClient pathProcessingClient;
    private final ScanSessionReader scanSessionReader;
    private final ScanStatusUpdater scanStatusUpdater;

    @Transactional
    public ProcessingStartResponse start(UUID buildingId, UUID sessionId) {
        ScanSession session = scanSessionReader.findEntityById(sessionId);

        if (!canStartProcessing(session.getStatus())) {
            throw new BusinessException(ErrorCode.INVALID_BUILDING_STATUS,
                "Scan session cannot be processed in current state: " + session.getStatus());
        }

        scanStatusUpdater.updateStatus(sessionId, ScanStatus.PROCESSING);

        // PLY 추출을 비동기로 시작 (사용자가 포인트클라우드 볼 때 바로 보이도록)
        extractPlyAsync(sessionId);

        return new ProcessingStartResponse(null, sessionId);
    }

    @Async
    public void extractPlyAsync(UUID sessionId) {
        try {
            ScanSession session = scanSessionReader.findEntityById(sessionId);
            String springPath = Paths.get(session.getFilePath()).toAbsolutePath().toString();
            String pythonPath = springPath.replace("/app/storage/uploads/", "/app/uploads/");

            log.info("PLY extraction started for session {}", sessionId);
            String cacheKey = pathProcessingClient.extractPointcloudPly(pythonPath);

            session.updatePlyFileId(cacheKey);
            scanStatusUpdater.updateStatus(sessionId, ScanStatus.COMPLETED);
            log.info("PLY extraction completed for session {} (cache_key: {})", sessionId, cacheKey);

        } catch (Exception e) {
            log.error("PLY extraction failed for session {}: {}", sessionId, e.getMessage());
            scanStatusUpdater.updateStatus(sessionId, ScanStatus.FAILED);
        }
    }

    public static String getJobIdForSession(UUID sessionId) {
        return null;
    }

    public static UUID getSessionIdForJob(String jobId) {
        return null;
    }

    private boolean canStartProcessing(ScanStatus status) {
        return status != ScanStatus.EXTRACTING && status != ScanStatus.PROCESSING;
    }
}
