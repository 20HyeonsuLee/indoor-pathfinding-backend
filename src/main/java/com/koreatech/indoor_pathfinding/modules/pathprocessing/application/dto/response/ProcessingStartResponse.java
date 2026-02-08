package com.koreatech.indoor_pathfinding.modules.pathprocessing.application.dto.response;

import java.util.UUID;

public record ProcessingStartResponse(
    String jobId,
    UUID sessionId
) {}
