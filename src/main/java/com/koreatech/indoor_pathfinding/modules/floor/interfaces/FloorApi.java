package com.koreatech.indoor_pathfinding.modules.floor.interfaces;

import com.koreatech.indoor_pathfinding.modules.floor.application.dto.request.FloorCreateRequest;
import com.koreatech.indoor_pathfinding.modules.floor.application.dto.request.FloorUpdateRequest;
import com.koreatech.indoor_pathfinding.modules.floor.application.dto.response.FloorPathResponse;
import com.koreatech.indoor_pathfinding.modules.floor.application.dto.response.FloorResponse;
import com.koreatech.indoor_pathfinding.shared.exception.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

@Tag(name = "Floor", description = "층 관리 API")
public interface FloorApi {

    @Operation(summary = "층 추가", description = "건물에 새로운 층을 추가합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "층 생성 성공"),
        @ApiResponse(responseCode = "404", description = "건물을 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<FloorResponse> addFloor(
        @Parameter(description = "건물 ID", required = true) UUID buildingId,
        FloorCreateRequest request
    );

    @Operation(summary = "건물의 층 목록 조회", description = "건물에 속한 모든 층 목록을 조회합니다.")
    @ApiResponse(responseCode = "200", description = "층 목록 조회 성공")
    ResponseEntity<List<FloorResponse>> getFloors(
        @Parameter(description = "건물 ID", required = true) UUID buildingId
    );

    @Operation(summary = "층 상세 조회", description = "층 ID로 상세 정보를 조회합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "층 조회 성공"),
        @ApiResponse(responseCode = "404", description = "층을 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<FloorResponse> getFloor(
        @Parameter(description = "층 ID", required = true) UUID floorId
    );

    @Operation(summary = "층 정보 수정", description = "층의 이름, 높이 등을 수정합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "층 수정 성공"),
        @ApiResponse(responseCode = "404", description = "층을 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<FloorResponse> updateFloor(
        @Parameter(description = "층 ID", required = true) UUID floorId,
        FloorUpdateRequest request
    );

    @Operation(summary = "층 삭제", description = "층을 삭제합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "층 삭제 성공"),
        @ApiResponse(responseCode = "404", description = "층을 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<Void> deleteFloor(
        @Parameter(description = "층 ID", required = true) UUID floorId
    );

    @Operation(summary = "층 경로 데이터 조회", description = "층의 경로 세그먼트 및 경계 정보를 조회합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "경로 데이터 조회 성공"),
        @ApiResponse(responseCode = "404", description = "층 또는 경로를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<FloorPathResponse> getFloorPath(
        @Parameter(description = "층 ID", required = true) UUID floorId
    );
}
