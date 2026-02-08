package com.koreatech.indoor_pathfinding.modules.scan.application.command;

import com.koreatech.indoor_pathfinding.modules.scan.application.dto.response.ScanSessionResponse;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ScanSession;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ScanStatus;
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
public class ScanStatusUpdater {

    private final ScanSessionRepository scanSessionRepository;

    public ScanSessionResponse updateStatus(UUID sessionId, ScanStatus status) {
        ScanSession session = scanSessionRepository.findById(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.SCAN_SESSION_NOT_FOUND));

        session.updateStatus(status);

        return ScanSessionResponse.from(session);
    }

    public ScanSessionResponse setError(UUID sessionId, String errorMessage) {
        ScanSession session = scanSessionRepository.findById(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.SCAN_SESSION_NOT_FOUND));

        session.setErrorMessage(errorMessage);

        return ScanSessionResponse.from(session);
    }
}
