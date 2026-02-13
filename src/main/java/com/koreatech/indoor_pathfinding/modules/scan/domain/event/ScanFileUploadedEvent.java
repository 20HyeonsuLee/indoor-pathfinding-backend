package com.koreatech.indoor_pathfinding.modules.scan.domain.event;

import java.util.UUID;

public record ScanFileUploadedEvent(
    UUID buildingId
) {
}
