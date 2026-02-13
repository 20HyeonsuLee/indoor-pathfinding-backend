package com.koreatech.indoor_pathfinding.modules.localization.application.dto.request;

import jakarta.validation.constraints.NotNull;

public record NodeImageRequest(
    @NotNull Double x,
    @NotNull Double y,
    @NotNull Double z
) {
}
