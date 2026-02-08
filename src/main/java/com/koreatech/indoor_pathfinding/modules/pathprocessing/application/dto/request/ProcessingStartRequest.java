package com.koreatech.indoor_pathfinding.modules.pathprocessing.application.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ProcessingStartRequest(
    @NotNull(message = "Session ID is required")
    UUID sessionId
) {}
