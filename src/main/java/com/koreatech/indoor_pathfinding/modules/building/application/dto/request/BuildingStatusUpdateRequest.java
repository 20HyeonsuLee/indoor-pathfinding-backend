package com.koreatech.indoor_pathfinding.modules.building.application.dto.request;

import com.koreatech.indoor_pathfinding.modules.building.domain.model.BuildingStatus;
import jakarta.validation.constraints.NotNull;

public record BuildingStatusUpdateRequest(
    @NotNull(message = "상태는 필수입니다")
    BuildingStatus status
) {
}
