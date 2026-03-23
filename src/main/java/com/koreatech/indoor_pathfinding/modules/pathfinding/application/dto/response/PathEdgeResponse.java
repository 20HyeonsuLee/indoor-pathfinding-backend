package com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.response;

import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.EdgeType;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.PathEdge;

import java.util.UUID;

public record PathEdgeResponse(
    UUID id,
    UUID fromNodeId,
    UUID toNodeId,
    Double distance,
    EdgeType edgeType,
    Boolean isBidirectional
) {
    public static PathEdgeResponse from(PathEdge edge) {
        return new PathEdgeResponse(
            edge.getId(),
            edge.getFromNode().getId(),
            edge.getToNode().getId(),
            edge.getDistance(),
            edge.getEdgeType(),
            edge.getIsBidirectional()
        );
    }
}
