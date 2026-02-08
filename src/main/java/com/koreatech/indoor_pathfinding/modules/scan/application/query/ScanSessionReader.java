package com.koreatech.indoor_pathfinding.modules.scan.application.query;

import com.koreatech.indoor_pathfinding.modules.building.domain.repository.BuildingRepository;
import com.koreatech.indoor_pathfinding.modules.scan.application.dto.response.ScanSessionResponse;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ScanSession;
import com.koreatech.indoor_pathfinding.modules.scan.domain.repository.ScanSessionRepository;
import com.koreatech.indoor_pathfinding.shared.exception.BusinessException;
import com.koreatech.indoor_pathfinding.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScanSessionReader {

    private final ScanSessionRepository scanSessionRepository;
    private final BuildingRepository buildingRepository;

    public List<ScanSessionResponse> findByBuildingId(UUID buildingId) {
        if (!buildingRepository.existsById(buildingId)) {
            throw new BusinessException(ErrorCode.BUILDING_NOT_FOUND);
        }

        return scanSessionRepository.findByBuildingId(buildingId).stream()
            .map(ScanSessionResponse::from)
            .toList();
    }

    public ScanSessionResponse findById(UUID sessionId) {
        ScanSession session = scanSessionRepository.findById(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.SCAN_SESSION_NOT_FOUND));

        return ScanSessionResponse.from(session);
    }

    public ScanSession findEntityById(UUID sessionId) {
        return scanSessionRepository.findById(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.SCAN_SESSION_NOT_FOUND));
    }
}
