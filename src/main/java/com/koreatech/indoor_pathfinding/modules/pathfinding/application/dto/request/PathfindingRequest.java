package com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.request;

import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.PathPreference;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PathfindingRequest(
    @NotNull(message = "출발 층은 필수입니다")
    Integer startFloorLevel,

    @NotNull(message = "출발 X 좌표는 필수입니다")
    Double startX,

    @NotNull(message = "출발 Y 좌표는 필수입니다")
    Double startY,

    Double startZ,

    @NotBlank(message = "목적지 이름은 필수입니다")
    String destinationName,

    PathPreference preference
) {
    public PathPreference getPreferenceOrDefault() {
        return preference != null ? preference : PathPreference.SHORTEST;
    }

    public double getStartZOrDefault() {
        return startZ != null ? startZ : 0.0;
    }
}
