package com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.request;

import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.PoiCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PoiRegisterRequest(
    @NotBlank(message = "POI 이름은 필수입니다")
    String name,

    @NotNull(message = "POI 카테고리는 필수입니다")
    PoiCategory category
) {}
