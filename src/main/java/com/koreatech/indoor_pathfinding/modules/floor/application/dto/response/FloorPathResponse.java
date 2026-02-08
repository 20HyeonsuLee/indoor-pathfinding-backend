package com.koreatech.indoor_pathfinding.modules.floor.application.dto.response;

import com.koreatech.indoor_pathfinding.modules.floor.domain.model.FloorPath;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record FloorPathResponse(
    UUID floorId,
    Double totalDistance,
    BoundsResponse bounds,
    List<SegmentResponse> segments
) {
    public record BoundsResponse(
        Double minX,
        Double maxX,
        Double minY,
        Double maxY
    ) {}

    public record SegmentResponse(
        int sequenceOrder,
        Point3DResponse startPoint,
        Point3DResponse endPoint,
        Double length
    ) {}

    public record Point3DResponse(
        Double x,
        Double y,
        Double z
    ) {}

    public static FloorPathResponse from(FloorPath floorPath) {
        List<SegmentResponse> segments = floorPath.getSegments().stream()
            .map(segment -> new SegmentResponse(
                segment.getSequenceOrder(),
                new Point3DResponse(
                    segment.getStartPoint().getX(),
                    segment.getStartPoint().getY(),
                    segment.getStartPoint().getZ()
                ),
                new Point3DResponse(
                    segment.getEndPoint().getX(),
                    segment.getEndPoint().getY(),
                    segment.getEndPoint().getZ()
                ),
                segment.getLength()
            ))
            .toList();

        return new FloorPathResponse(
            floorPath.getFloor().getId(),
            floorPath.getTotalDistance(),
            new BoundsResponse(
                floorPath.getMinX(),
                floorPath.getMaxX(),
                floorPath.getMinY(),
                floorPath.getMaxY()
            ),
            segments
        );
    }
}
