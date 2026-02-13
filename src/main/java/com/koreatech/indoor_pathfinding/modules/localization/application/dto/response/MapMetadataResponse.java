package com.koreatech.indoor_pathfinding.modules.localization.application.dto.response;

import java.util.Map;

public record MapMetadataResponse(
    String mapId,
    String buildingId,
    Integer numKeyframes,
    String createdAt,
    String status
) {

    public static MapMetadataResponse from(final Map<String, Object> raw) {
        return new MapMetadataResponse(
            (String) raw.get("map_id"),
            (String) raw.get("building_id"),
            raw.get("num_keyframes") != null ? ((Number) raw.get("num_keyframes")).intValue() : null,
            (String) raw.get("created_at"),
            (String) raw.get("status")
        );
    }
}
