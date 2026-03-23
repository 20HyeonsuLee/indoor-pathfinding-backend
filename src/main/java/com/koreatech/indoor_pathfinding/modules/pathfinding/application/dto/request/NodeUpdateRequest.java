package com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.request;

import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.NodeType;

public record NodeUpdateRequest(
    Double x,
    Double y,
    Double z,
    NodeType type
) {}
