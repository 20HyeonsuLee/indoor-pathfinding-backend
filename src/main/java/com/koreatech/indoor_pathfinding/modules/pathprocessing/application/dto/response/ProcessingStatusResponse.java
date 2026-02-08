package com.koreatech.indoor_pathfinding.modules.pathprocessing.application.dto.response;

public record ProcessingStatusResponse(
    String jobId,
    String status,
    int progress,
    String message,
    String createdAt,
    String completedAt,
    String error
) {}
