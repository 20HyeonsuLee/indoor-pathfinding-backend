package com.koreatech.indoor_pathfinding.modules.scan.application.query;

import com.koreatech.indoor_pathfinding.modules.scan.application.dto.response.MergedScanResponse;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.MergedScan;
import com.koreatech.indoor_pathfinding.modules.scan.domain.repository.MergedScanRepository;
import com.koreatech.indoor_pathfinding.shared.exception.BusinessException;
import com.koreatech.indoor_pathfinding.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MergedScanReader {

    private final MergedScanRepository mergedScanRepository;

    public MergedScanResponse findByFloorId(final UUID floorId) {
        return mergedScanRepository.findByFloorId(floorId)
            .map(MergedScanResponse::from)
            .orElse(null);
    }

    public MergedScan findEntityByFloorId(final UUID floorId) {
        return mergedScanRepository.findByFloorId(floorId)
            .orElseThrow(() -> new BusinessException(ErrorCode.MERGED_SCAN_NOT_FOUND));
    }
}
