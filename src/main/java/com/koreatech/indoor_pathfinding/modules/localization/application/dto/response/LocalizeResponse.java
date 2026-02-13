package com.koreatech.indoor_pathfinding.modules.localization.application.dto.response;

import java.util.Map;

public record LocalizeResponse(
    Map<String, Object> pose,
    Double confidence,
    String mapId,
    Integer numMatches
) {

    @SuppressWarnings("unchecked")
    public static LocalizeResponse from(final Map<String, Object> raw) {
        return new LocalizeResponse(
            (Map<String, Object>) raw.get("pose"),
            raw.get("confidence") != null ? ((Number) raw.get("confidence")).doubleValue() : null,
            (String) raw.get("map_id"),
            raw.get("num_matches") != null ? ((Number) raw.get("num_matches")).intValue() : null
        );
    }
}
