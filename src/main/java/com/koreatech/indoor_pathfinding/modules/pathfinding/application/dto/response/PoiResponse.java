package com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.response;

import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.PathNode;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.PoiCategory;

import java.util.UUID;

public record PoiResponse(
    UUID nodeId,
    String name,
    PoiCategory category,
    int floorLevel,
    String floorName,
    double x,
    double y,
    double z
) {
    public static PoiResponse from(PathNode node) {
        return new PoiResponse(
            node.getId(),
            node.getPoiName(),
            node.getPoiCategory(),
            node.getFloor().getLevel(),
            node.getFloor().getName(),
            node.getX(),
            node.getY(),
            node.getZ()
        );
    }
}
