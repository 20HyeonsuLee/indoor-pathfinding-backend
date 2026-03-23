package com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.request;

import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.EdgeType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record EdgeCreateRequest(
    @NotNull(message = "시작 노드 ID는 필수입니다")
    UUID fromNodeId,

    @NotNull(message = "도착 노드 ID는 필수입니다")
    UUID toNodeId,

    EdgeType edgeType,

    Boolean isBidirectional
) {
    public EdgeType edgeTypeOrDefault() {
        return edgeType != null ? edgeType : EdgeType.HORIZONTAL;
    }

    public boolean bidirectionalOrDefault() {
        return isBidirectional != null ? isBidirectional : true;
    }
}
