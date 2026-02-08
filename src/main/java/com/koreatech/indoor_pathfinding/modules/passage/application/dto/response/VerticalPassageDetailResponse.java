package com.koreatech.indoor_pathfinding.modules.passage.application.dto.response;

import com.koreatech.indoor_pathfinding.modules.passage.domain.model.PassageType;
import com.koreatech.indoor_pathfinding.modules.passage.domain.model.VerticalPassage;

import java.util.List;
import java.util.UUID;

public record VerticalPassageDetailResponse(
    UUID id,
    PassageType type,
    UUID fromFloorId,
    int fromFloorLevel,
    UUID toFloorId,
    int toFloorLevel,
    Point3DResponse entryPoint,
    Point3DResponse exitPoint,
    List<SegmentResponse> segments
) {
    public record Point3DResponse(
        Double x,
        Double y,
        Double z
    ) {}

    public record SegmentResponse(
        int sequenceOrder,
        Point3DResponse startPoint,
        Point3DResponse endPoint,
        Double length
    ) {}

    public static VerticalPassageDetailResponse from(VerticalPassage passage) {
        Point3DResponse entry = null;
        Point3DResponse exit = null;

        if (passage.getEntryX() != null) {
            entry = new Point3DResponse(
                passage.getEntryX(),
                passage.getEntryY(),
                passage.getEntryZ()
            );
        }

        if (passage.getExitX() != null) {
            exit = new Point3DResponse(
                passage.getExitX(),
                passage.getExitY(),
                passage.getExitZ()
            );
        }

        List<SegmentResponse> segments = passage.getSegments().stream()
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

        return new VerticalPassageDetailResponse(
            passage.getId(),
            passage.getType(),
            passage.getFromFloor().getId(),
            passage.getFromFloor().getLevel(),
            passage.getToFloor().getId(),
            passage.getToFloor().getLevel(),
            entry,
            exit,
            segments
        );
    }
}
