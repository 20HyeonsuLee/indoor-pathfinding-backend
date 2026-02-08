package com.koreatech.indoor_pathfinding.modules.pathfinding.interfaces;

import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.request.PathfindingRequest;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.response.PathfindingResponse;
import com.koreatech.indoor_pathfinding.shared.exception.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

@Tag(name = "Pathfinding", description = "실내 길찾기 API")
public interface PathfindingApi {

    @Operation(
        summary = "경로 탐색",
        description = """
            출발 좌표와 목적지 이름을 기반으로 최적 경로를 탐색합니다.
            A* 알고리즘을 사용하며, 경로 선호도(최단거리, 엘리베이터 우선, 계단 우선)를 지정할 수 있습니다.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "경로 탐색 성공"),
        @ApiResponse(responseCode = "400", description = "잘못된 입력값",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "목적지를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<PathfindingResponse> findPath(
        @Parameter(description = "건물 ID", required = true) UUID buildingId,
        PathfindingRequest request
    );
}
