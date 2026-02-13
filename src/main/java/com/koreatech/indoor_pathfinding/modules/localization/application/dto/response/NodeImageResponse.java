package com.koreatech.indoor_pathfinding.modules.localization.application.dto.response;

import com.koreatech.indoor_pathfinding.modules.localization.infrastructure.persistence.RtabMapImageExtractor.NearbyNodeImage;

public record NodeImageResponse(
    int nodeId,
    double x,
    double y,
    double z,
    double distance,
    double cameraAngle,
    String imageUrl
) {

    public static NodeImageResponse of(final NearbyNodeImage node, final String imageUrl) {
        return new NodeImageResponse(
            node.nodeId(),
            node.x(),
            node.y(),
            node.z(),
            node.distance(),
            node.cameraAngle(),
            imageUrl
        );
    }
}
