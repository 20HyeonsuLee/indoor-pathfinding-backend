package com.koreatech.indoor_pathfinding.modules.building.application.dto.response;

import com.koreatech.indoor_pathfinding.modules.building.domain.model.Building;
import com.koreatech.indoor_pathfinding.modules.building.domain.model.BuildingStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record BuildingResponse(
    UUID id,
    String name,
    String description,
    Double latitude,
    Double longitude,
    BuildingStatus status,
    int floorCount,
    int passageCount,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public static BuildingResponse from(Building building) {
        Double lat = null;
        Double lng = null;

        if (building.getLocation() != null) {
            lat = building.getLocation().getY();
            lng = building.getLocation().getX();
        }

        return new BuildingResponse(
            building.getId(),
            building.getName(),
            building.getDescription(),
            lat,
            lng,
            building.getStatus(),
            building.getFloors().size(),
            building.getVerticalPassages().size(),
            building.getCreatedAt(),
            building.getUpdatedAt()
        );
    }
}
