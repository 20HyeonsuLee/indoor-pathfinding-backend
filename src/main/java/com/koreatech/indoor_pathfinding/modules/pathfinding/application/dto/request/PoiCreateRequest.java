package com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.request;

import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.PoiCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PoiCreateRequest(
    @NotBlank(message = "POI 이름은 필수입니다")
    String name,

    @NotNull(message = "POI 카테고리는 필수입니다")
    PoiCategory category,

    @NotNull(message = "층 번호는 필수입니다")
    Integer floorLevel,

    @NotNull(message = "X 좌표는 필수입니다")
    Double x,

    @NotNull(message = "Y 좌표는 필수입니다")
    Double y,

    Double z
) {
    public double getZOrDefault() {
        return z != null ? z : 0.0;
    }
}
