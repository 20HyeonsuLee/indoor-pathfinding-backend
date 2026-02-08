package com.koreatech.indoor_pathfinding.modules.floor.application.dto.request;

import jakarta.validation.constraints.Size;

public record FloorUpdateRequest(
    @Size(max = 50, message = "Floor name must be at most 50 characters")
    String name,

    Double height
) {}
