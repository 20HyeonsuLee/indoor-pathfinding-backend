package com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.response;

import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.NodeType;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.PathNode;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.PoiCategory;

import java.util.UUID;

public record PathNodeResponse(
    UUID id,
    UUID floorId,
    Double x,
    Double y,
    Double z,
    NodeType type,
    String poiName,
    PoiCategory poiCategory
) {
    public static PathNodeResponse from(PathNode node) {
        return new PathNodeResponse(
            node.getId(),
            node.getFloor().getId(),
            node.getX(),
            node.getY(),
            node.getZ(),
            node.getType(),
            node.getPoiName(),
            node.getPoiCategory()
        );
    }
}
