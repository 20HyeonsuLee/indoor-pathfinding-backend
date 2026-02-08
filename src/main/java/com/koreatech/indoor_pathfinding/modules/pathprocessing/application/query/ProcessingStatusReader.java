package com.koreatech.indoor_pathfinding.modules.pathprocessing.application.query;

import com.koreatech.indoor_pathfinding.modules.pathprocessing.application.command.ProcessingStarter;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.application.dto.response.ProcessingStatusResponse;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.infrastructure.external.PathProcessingClient;
import com.koreatech.indoor_pathfinding.shared.exception.BusinessException;
import com.koreatech.indoor_pathfinding.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProcessingStatusReader {

    private final PathProcessingClient pathProcessingClient;

    public ProcessingStatusResponse getStatus(UUID buildingId, UUID sessionId) {
        String jobId = ProcessingStarter.getJobIdForSession(sessionId);

        if (jobId == null) {
            throw new BusinessException(ErrorCode.SCAN_SESSION_NOT_FOUND,
                "No processing job found for session");
        }

        return pathProcessingClient.getJobStatus(jobId);
    }
}
