package com.koreatech.indoor_pathfinding.modules.scan.application.dto.response;

import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ChunkStatus;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ScanChunk;

import java.time.LocalDateTime;
import java.util.UUID;

public record ScanChunkResponse(
    UUID id,
    UUID floorId,
    String fileName,
    Long fileSize,
    ChunkStatus status,
    boolean active,
    int uploadOrder,
    String errorMessage,
    LocalDateTime createdAt
) {
    public static ScanChunkResponse from(final ScanChunk chunk) {
        return new ScanChunkResponse(
            chunk.getId(),
            chunk.getFloor().getId(),
            chunk.getFileName(),
            chunk.getFileSize(),
            chunk.getStatus(),
            chunk.isActive(),
            chunk.getUploadOrder(),
            chunk.getErrorMessage(),
            chunk.getCreatedAt()
        );
    }
}
