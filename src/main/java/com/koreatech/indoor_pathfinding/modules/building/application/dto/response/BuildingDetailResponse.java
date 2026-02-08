package com.koreatech.indoor_pathfinding.modules.building.application.dto.response;

import com.koreatech.indoor_pathfinding.modules.building.domain.model.Building;
import com.koreatech.indoor_pathfinding.modules.building.domain.model.BuildingStatus;
import com.koreatech.indoor_pathfinding.modules.floor.application.dto.response.FloorResponse;
import com.koreatech.indoor_pathfinding.modules.passage.application.dto.response.VerticalPassageResponse;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record BuildingDetailResponse(
    UUID id,
    String name,
    String description,
    Double latitude,
    Double longitude,
    BuildingStatus status,
    List<FloorResponse> floors,
    List<VerticalPassageResponse> verticalPassages,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public static BuildingDetailResponse from(Building building) {
        Double lat = null;
        Double lng = null;

        if (building.getLocation() != null) {
            lat = building.getLocation().getY();
            lng = building.getLocation().getX();
        }

        List<FloorResponse> floors = building.getFloors().stream()
            .sorted(Comparator.comparingInt(f -> f.getLevel()))
            .map(FloorResponse::from)
            .toList();

        List<VerticalPassageResponse> passages = building.getVerticalPassages().stream()
            .map(VerticalPassageResponse::from)
            .toList();

        return new BuildingDetailResponse(
            building.getId(),
            building.getName(),
            building.getDescription(),
            lat,
            lng,
            building.getStatus(),
            floors,
            passages,
            building.getCreatedAt(),
            building.getUpdatedAt()
        );
    }
}
