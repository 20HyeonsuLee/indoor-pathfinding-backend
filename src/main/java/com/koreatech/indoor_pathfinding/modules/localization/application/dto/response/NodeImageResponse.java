package com.koreatech.indoor_pathfinding.modules.localization.application.dto.response;

import com.koreatech.indoor_pathfinding.modules.localization.infrastructure.persistence.RtabMapImageExtractor.NearbyNodeImage;

import java.util.Base64;

public record NodeImageResponse(
    int nodeId,
    double x,
    double y,
    double z,
    double distance,
    double cameraAngle,
    String imageUrl
) {

    private static final String DATA_URI_PREFIX = "data:image/jpeg;base64,";

    public static NodeImageResponse from(final NearbyNodeImage node) {
        final String dataUri = DATA_URI_PREFIX + Base64.getEncoder().encodeToString(node.imageData());
        return new NodeImageResponse(
            node.nodeId(),
            node.x(),
            node.y(),
            node.z(),
            node.distance(),
            node.cameraAngle(),
            dataUri
        );
    }
}
