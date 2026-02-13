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
    BUILDING_ALREADY_EXISTS(HttpStatus.CONFLICT, "B002", "Building already exists"),
    INVALID_BUILDING_STATUS(HttpStatus.BAD_REQUEST, "B003", "Invalid building status"),

    // Floor
    FLOOR_NOT_FOUND(HttpStatus.NOT_FOUND, "F001", "Floor not found"),
    FLOOR_ALREADY_EXISTS(HttpStatus.CONFLICT, "F002", "Floor already exists"),
    INVALID_FLOOR_LEVEL(HttpStatus.BAD_REQUEST, "F003", "Invalid floor level"),

    // ScanSession
    SCAN_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "S001", "Scan session not found"),
    SCAN_PROCESSING_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "S002", "Scan processing failed"),
    INVALID_SCAN_FILE(HttpStatus.BAD_REQUEST, "S003", "Invalid scan file"),

    // Path
    PATH_NOT_FOUND(HttpStatus.NOT_FOUND, "P001", "Path not found"),
    PATH_PROCESSING_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "P002", "Path processing failed"),

    // Pathfinding
    NODE_NOT_FOUND(HttpStatus.NOT_FOUND, "PF001", "Node not found"),
    POI_NOT_FOUND(HttpStatus.NOT_FOUND, "PF002", "POI not found"),
    GRAPH_NOT_BUILT(HttpStatus.BAD_REQUEST, "PF003", "Graph not built for this building"),
    DESTINATION_NOT_FOUND(HttpStatus.NOT_FOUND, "PF004", "Destination not found"),
    NO_PATH_AVAILABLE(HttpStatus.NOT_FOUND, "PF005", "No path available between the specified points"),

    // VPS
    VPS_SERVICE_ERROR(HttpStatus.SERVICE_UNAVAILABLE, "V001", "VPS service error"),
    LOCALIZATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "V002", "Localization failed"),

    // External Service
    EXTERNAL_SERVICE_ERROR(HttpStatus.SERVICE_UNAVAILABLE, "E001", "External service error");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
