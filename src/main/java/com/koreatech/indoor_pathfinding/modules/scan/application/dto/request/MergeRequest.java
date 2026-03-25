package com.koreatech.indoor_pathfinding.modules.scan.application.dto.request;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

public record MergeRequest(
    @NotEmpty(message = "At least one chunk ID is required")
    List<UUID> chunkIds
) {}
