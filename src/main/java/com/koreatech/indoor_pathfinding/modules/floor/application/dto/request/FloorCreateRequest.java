package com.koreatech.indoor_pathfinding.modules.floor.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record FloorCreateRequest(
    @NotBlank(message = "Floor name is required")
    @Size(max = 50, message = "Floor name must be at most 50 characters")
    String name,

    @NotNull(message = "Floor level is required")
    Integer level,

    Double height
) {}
