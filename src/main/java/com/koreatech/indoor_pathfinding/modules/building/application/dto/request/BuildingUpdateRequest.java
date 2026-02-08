package com.koreatech.indoor_pathfinding.modules.building.application.dto.request;

import jakarta.validation.constraints.Size;

public record BuildingUpdateRequest(
    @Size(max = 100, message = "Building name must be at most 100 characters")
    String name,

    @Size(max = 1000, message = "Description must be at most 1000 characters")
    String description,

    Double latitude,

    Double longitude
) {}
