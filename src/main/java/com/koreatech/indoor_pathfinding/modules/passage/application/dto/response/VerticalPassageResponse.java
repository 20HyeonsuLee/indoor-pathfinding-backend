package com.koreatech.indoor_pathfinding.modules.passage.application.dto.response;

import com.koreatech.indoor_pathfinding.modules.passage.domain.model.PassageType;
import com.koreatech.indoor_pathfinding.modules.passage.domain.model.VerticalPassage;

import java.util.UUID;

public record VerticalPassageResponse(
    UUID id,
    PassageType type,
    UUID fromFloorId,
    int fromFloorLevel,
    UUID toFloorId,
    int toFloorLevel,
    Point3DResponse entryPoint,
    Point3DResponse exitPoint
) {
    public record Point3DResponse(
        Double x,
        Double y,
        Double z
    ) {}

    public static VerticalPassageResponse from(VerticalPassage passage) {
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

        return new VerticalPassageResponse(
            passage.getId(),
            passage.getType(),
            passage.getFromFloor().getId(),
            passage.getFromFloor().getLevel(),
            passage.getToFloor().getId(),
            passage.getToFloor().getLevel(),
            entry,
            exit
        );
    }
}
