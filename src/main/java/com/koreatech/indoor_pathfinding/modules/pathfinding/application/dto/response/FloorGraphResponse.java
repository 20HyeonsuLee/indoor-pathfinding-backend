package com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.response;

import java.util.List;
import java.util.UUID;

public record FloorGraphResponse(
    UUID floorId,
    List<PathNodeResponse> nodes,
    List<PathEdgeResponse> edges
) {}
