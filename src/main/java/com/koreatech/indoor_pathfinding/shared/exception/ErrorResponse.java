package com.koreatech.indoor_pathfinding.shared.exception;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "에러 응답")
public record ErrorResponse(
    @Schema(description = "에러 발생 시각", example = "2026-02-08T12:00:00")
    LocalDateTime timestamp,

    @Schema(description = "에러 코드", example = "B001")
    String code,

    @Schema(description = "에러 메시지", example = "Building not found")
    String message,

    @Schema(description = "HTTP 상태 코드", example = "404")
    int status
) {}
