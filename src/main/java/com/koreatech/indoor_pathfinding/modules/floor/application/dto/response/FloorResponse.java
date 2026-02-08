package com.koreatech.indoor_pathfinding.modules.floor.application.dto.response;

import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;

import java.util.UUID;

public record FloorResponse(
    UUID id,
    String name,
    int level,
    Double height,
    boolean hasPath
) {
    public static FloorResponse from(Floor floor) {
        return new FloorResponse(
            floor.getId(),
            floor.getName(),
            floor.getLevel(),
            floor.getHeight(),
            floor.getFloorPath() != null
        );
    }
}
