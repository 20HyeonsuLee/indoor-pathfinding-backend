package com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.request;

import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.NodeType;
import jakarta.validation.constraints.NotNull;

public record NodeCreateRequest(
    @NotNull(message = "x 좌표는 필수입니다")
    Double x,

    @NotNull(message = "y 좌표는 필수입니다")
    Double y,

    Double z,

    @NotNull(message = "노드 타입은 필수입니다")
    NodeType type
) {
    public double zOrDefault() {
        return z != null ? z : 0.0;
    }
}
