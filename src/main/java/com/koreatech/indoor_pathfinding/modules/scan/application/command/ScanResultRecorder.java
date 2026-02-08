package com.koreatech.indoor_pathfinding.modules.scan.application.command;

import com.koreatech.indoor_pathfinding.modules.scan.application.dto.response.ScanSessionResponse;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ScanSession;
import com.koreatech.indoor_pathfinding.modules.scan.domain.repository.ScanSessionRepository;
import com.koreatech.indoor_pathfinding.shared.exception.BusinessException;
import com.koreatech.indoor_pathfinding.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ScanResultRecorder {

    private final ScanSessionRepository scanSessionRepository;

    public ScanSessionResponse recordResult(UUID sessionId, String previewImagePath,
                                             String processedPreviewPath,
                                             int totalNodes, double totalDistance
    ) {
        ScanSession session = scanSessionRepository.findById(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.SCAN_SESSION_NOT_FOUND));

        session.updateProcessingResult(previewImagePath, processedPreviewPath, totalNodes, totalDistance);

        return ScanSessionResponse.from(session);
    }
}
