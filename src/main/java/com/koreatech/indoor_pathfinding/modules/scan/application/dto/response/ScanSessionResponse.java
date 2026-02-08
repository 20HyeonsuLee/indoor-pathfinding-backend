package com.koreatech.indoor_pathfinding.modules.scan.application.dto.response;

import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ScanSession;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ScanStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record ScanSessionResponse(
    UUID id,
    UUID buildingId,
    String fileName,
    Long fileSize,
    ScanStatus status,
    String errorMessage,
    String previewImagePath,
    String processedPreviewPath,
    Integer totalNodes,
    Double totalDistance,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public static ScanSessionResponse from(ScanSession session) {
        return new ScanSessionResponse(
            session.getId(),
            session.getBuilding().getId(),
            session.getFileName(),
            session.getFileSize(),
            session.getStatus(),
            session.getErrorMessage(),
            session.getPreviewImagePath(),
            session.getProcessedPreviewPath(),
            session.getTotalNodes(),
            session.getTotalDistance(),
            session.getCreatedAt(),
            session.getUpdatedAt()
        );
    }
}
