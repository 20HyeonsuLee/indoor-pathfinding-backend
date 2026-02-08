package com.koreatech.indoor_pathfinding.modules.pathfinding.interfaces;

import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.request.PoiCreateRequest;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.request.PoiRegisterRequest;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.response.PoiResponse;
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

@Tag(name = "POI", description = "관심지점(POI) 관리 API")
public interface PoiApi {

    @Operation(summary = "POI 생성", description = "새로운 관심지점(POI)을 생성합니다. 가장 가까운 경로 노드에 자동으로 연결됩니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "POI 생성 성공"),
        @ApiResponse(responseCode = "404", description = "건물 또는 노드를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<PoiResponse> createPoi(
        @Parameter(description = "건물 ID", required = true) UUID buildingId,
        PoiCreateRequest request
    );

    @Operation(summary = "노드에 POI 등록", description = "기존 경로 노드에 POI 정보를 등록합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "POI 등록 성공"),
        @ApiResponse(responseCode = "404", description = "노드를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<PoiResponse> registerPoi(
        @Parameter(description = "노드 ID", required = true) UUID nodeId,
        PoiRegisterRequest request
    );

    @Operation(summary = "POI 삭제", description = "노드에서 POI 정보를 제거합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "POI 삭제 성공"),
        @ApiResponse(responseCode = "404", description = "노드를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<Void> deletePoi(
        @Parameter(description = "노드 ID", required = true) UUID nodeId
    );

    @Operation(summary = "건물의 POI 목록 조회", description = "건물에 등록된 모든 POI를 조회합니다.")
    @ApiResponse(responseCode = "200", description = "POI 목록 조회 성공")
    ResponseEntity<List<PoiResponse>> getPoiList(
        @Parameter(description = "건물 ID", required = true) UUID buildingId
    );

    @Operation(summary = "POI 검색", description = "건물 내 POI를 이름으로 검색합니다.")
    @ApiResponse(responseCode = "200", description = "POI 검색 성공")
    ResponseEntity<List<PoiResponse>> searchPois(
        @Parameter(description = "건물 ID", required = true) UUID buildingId,
        @Parameter(description = "검색 키워드") String query
    );
}
