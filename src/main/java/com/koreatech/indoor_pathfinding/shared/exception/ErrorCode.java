package com.koreatech.indoor_pathfinding.shared.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Common
    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "C001", "Invalid input value"),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C002", "Internal server error"),
    ENTITY_NOT_FOUND(HttpStatus.NOT_FOUND, "C003", "Entity not found"),

    // Building
    BUILDING_NOT_FOUND(HttpStatus.NOT_FOUND, "B001", "Building not found"),
    INVALID_BUILDING_STATUS(HttpStatus.BAD_REQUEST, "B003", "Invalid building status"),

    // Floor
    FLOOR_NOT_FOUND(HttpStatus.NOT_FOUND, "F001", "Floor not found"),
    FLOOR_ALREADY_EXISTS(HttpStatus.CONFLICT, "F002", "Floor already exists"),

    // ScanChunk
    SCAN_CHUNK_NOT_FOUND(HttpStatus.NOT_FOUND, "SC001", "Scan chunk not found"),
    INVALID_SCAN_FILE(HttpStatus.BAD_REQUEST, "SC002", "Invalid scan file"),
    NO_ACTIVE_CHUNKS(HttpStatus.BAD_REQUEST, "SC003", "No active chunks to merge"),

    // MergedScan
    MERGED_SCAN_NOT_FOUND(HttpStatus.NOT_FOUND, "MS001", "Merged scan not found"),
    MERGE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "MS002", "Merge processing failed"),
    MERGED_SCAN_NOT_PROCESSABLE(HttpStatus.BAD_REQUEST, "MS003", "Merged scan is not in a processable state"),
    SCAN_PROCESSING_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "MS004", "Scan processing failed"),

    // Path
    PATH_NOT_FOUND(HttpStatus.NOT_FOUND, "P001", "Path not found"),

    // Pathfinding
    NODE_NOT_FOUND(HttpStatus.NOT_FOUND, "PF001", "Node not found"),
    POI_NOT_FOUND(HttpStatus.NOT_FOUND, "PF002", "POI not found"),
    GRAPH_NOT_BUILT(HttpStatus.BAD_REQUEST, "PF003", "Graph not built for this building"),
    NO_PATH_AVAILABLE(HttpStatus.NOT_FOUND, "PF005", "No path available between the specified points"),
    EDGE_NOT_FOUND(HttpStatus.NOT_FOUND, "PF006", "Edge not found"),
    DUPLICATE_EDGE(HttpStatus.CONFLICT, "PF007", "Edge already exists between these nodes"),

    // VPS
    VPS_SERVICE_ERROR(HttpStatus.SERVICE_UNAVAILABLE, "V001", "VPS service error"),
    LOCALIZATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "V002", "Localization failed"),

    // External Service
    EXTERNAL_SERVICE_ERROR(HttpStatus.SERVICE_UNAVAILABLE, "E001", "External service error");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
