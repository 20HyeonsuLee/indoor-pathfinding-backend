package com.koreatech.indoor_pathfinding.modules.localization.application.dto.response;

import java.util.Map;

public record SlamStatusResponse(
    Map<String, Object> data
) {

    public static SlamStatusResponse from(final Map<String, Object> raw) {
        return new SlamStatusResponse(raw);
    }
}
