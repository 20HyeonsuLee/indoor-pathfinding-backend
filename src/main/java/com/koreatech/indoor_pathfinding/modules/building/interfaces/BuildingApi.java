package com.koreatech.indoor_pathfinding.modules.building.interfaces;

import com.koreatech.indoor_pathfinding.modules.building.application.dto.request.BuildingCreateRequest;
import com.koreatech.indoor_pathfinding.modules.building.application.dto.request.BuildingStatusUpdateRequest;
import com.koreatech.indoor_pathfinding.modules.building.application.dto.request.BuildingUpdateRequest;
import com.koreatech.indoor_pathfinding.modules.building.application.dto.response.BuildingDetailResponse;
import com.koreatech.indoor_pathfinding.modules.building.application.dto.response.BuildingResponse;
import com.koreatech.indoor_pathfinding.modules.building.domain.model.BuildingStatus;
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

@Tag(name = "Building", description = "건물 관리 API")
public interface BuildingApi {

    @Operation(summary = "건물 생성", description = "새로운 건물을 등록합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "건물 생성 성공"),
        @ApiResponse(responseCode = "400", description = "잘못된 입력값",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<BuildingResponse> createBuilding(BuildingCreateRequest request);

    @Operation(summary = "건물 목록 조회", description = "모든 건물 목록을 조회합니다. 상태별 필터링이 가능합니다.")
    @ApiResponse(responseCode = "200", description = "건물 목록 조회 성공")
    ResponseEntity<List<BuildingResponse>> getAllBuildings(
        @Parameter(description = "건물 상태 필터") BuildingStatus status
    );

    @Operation(summary = "건물 상세 조회", description = "건물 ID로 상세 정보(층, 수직통로 포함)를 조회합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "건물 상세 조회 성공"),
        @ApiResponse(responseCode = "404", description = "건물을 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<BuildingDetailResponse> getBuilding(
        @Parameter(description = "건물 ID", required = true) UUID id
    );

    @Operation(summary = "건물 정보 수정", description = "건물의 이름, 설명, 좌표 등을 수정합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "건물 수정 성공"),
        @ApiResponse(responseCode = "404", description = "건물을 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<BuildingResponse> updateBuilding(
        @Parameter(description = "건물 ID", required = true) UUID id,
        BuildingUpdateRequest request
    );

    @Operation(summary = "건물 삭제", description = "건물을 삭제합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "건물 삭제 성공"),
        @ApiResponse(responseCode = "404", description = "건물을 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<Void> deleteBuilding(
        @Parameter(description = "건물 ID", required = true) UUID id
    );

    @Operation(summary = "건물 상태 변경", description = "건물의 상태를 변경합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "상태 변경 성공"),
        @ApiResponse(responseCode = "404", description = "건물을 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<BuildingResponse> updateBuildingStatus(
        @Parameter(description = "건물 ID", required = true) UUID id,
        BuildingStatusUpdateRequest request
    );
}
