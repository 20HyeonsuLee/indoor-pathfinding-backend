package com.koreatech.indoor_pathfinding.modules.localization.application.dto.response;

import java.util.Map;

public record SlamProcessResponse(
    String mapId,
    String status,
    Integer queuePosition
) {

    public static SlamProcessResponse from(final Map<String, Object> raw) {
        return new SlamProcessResponse(
            (String) raw.get("map_id"),
            (String) raw.get("status"),
            raw.get("queue_position") != null ? ((Number) raw.get("queue_position")).intValue() : null
        );
    }
}
