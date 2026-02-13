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
    String imageBase64
) {

    public static NodeImageResponse from(final NearbyNodeImage node) {
        return new NodeImageResponse(
            node.nodeId(),
            node.x(),
            node.y(),
            node.z(),
            node.distance(),
            node.cameraAngle(),
            Base64.getEncoder().encodeToString(node.imageData())
        );
    }
}
