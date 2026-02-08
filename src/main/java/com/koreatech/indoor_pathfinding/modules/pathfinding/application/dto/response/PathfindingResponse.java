package com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.response;

import java.util.List;

public record PathfindingResponse(
    double totalDistance,
    int estimatedTimeSeconds,
    List<PathStep> steps,
    List<FloorTransition> floorTransitions
) {
    public record PathStep(
        int stepNumber,
        int floorLevel,
        Position position,
        String instruction
    ) {}

    public record Position(
        double x,
        double y,
        double z
    ) {}

    public record FloorTransition(
        int fromFloor,
        int toFloor,
        String passageType
    ) {}
}
