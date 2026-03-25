package com.koreatech.indoor_pathfinding.modules.scan.application.dto.response;

import com.koreatech.indoor_pathfinding.modules.scan.domain.model.MergedScan;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.MergedScanStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record MergedScanResponse(
    UUID id,
    UUID floorId,
    MergedScanStatus status,
    String plyFileId,
    Integer totalNodes,
    Double totalDistance,
    String errorMessage,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public static MergedScanResponse from(final MergedScan mergedScan) {
        return new MergedScanResponse(
            mergedScan.getId(),
            mergedScan.getFloor().getId(),
            mergedScan.getStatus(),
            mergedScan.getPlyFileId(),
            mergedScan.getTotalNodes(),
            mergedScan.getTotalDistance(),
            mergedScan.getErrorMessage(),
            mergedScan.getCreatedAt(),
            mergedScan.getUpdatedAt()
        );
    }
}
