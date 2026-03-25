package com.koreatech.indoor_pathfinding.modules.scan.domain.event;

import java.util.List;
import java.util.UUID;

public record MergedScanCreatedEvent(
    UUID mergedScanId,
    UUID floorId,
    boolean needsMerge,
    List<String> chunkPaths,
    String outputPath
) {
    public static MergedScanCreatedEvent singleChunk(final UUID mergedScanId, final UUID floorId) {
        return new MergedScanCreatedEvent(mergedScanId, floorId, false, null, null);
    }

    public static MergedScanCreatedEvent multiChunk(final UUID mergedScanId, final UUID floorId,
                                                     final List<String> chunkPaths, final String outputPath) {
        return new MergedScanCreatedEvent(mergedScanId, floorId, true, chunkPaths, outputPath);
    }
}
