package com.koreatech.indoor_pathfinding.modules.localization.interfaces;

import com.koreatech.indoor_pathfinding.modules.localization.application.dto.request.NodeImageRequest;
import com.koreatech.indoor_pathfinding.modules.localization.application.dto.response.LocalizeResponse;
import com.koreatech.indoor_pathfinding.modules.localization.application.dto.response.MapMetadataResponse;
import com.koreatech.indoor_pathfinding.modules.localization.application.dto.response.NodeImageResponse;
import com.koreatech.indoor_pathfinding.modules.localization.application.dto.response.SlamStatusResponse;
import com.koreatech.indoor_pathfinding.shared.exception.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Tag(name = "Localization", description = "VPS 기반 실내 위치 추정 API")
public interface LocalizationApi {

    @Operation(summary = "현재 위치 추정", description = "카메라 이미지를 업로드하여 건물 내 현재 위치를 추정합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "위치 추정 성공"),
        @ApiResponse(responseCode = "400", description = "잘못된 요청",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "VPS 서비스 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<LocalizeResponse> localize(
        @Parameter(description = "건물 ID", required = true) UUID buildingId,
        @Parameter(description = "카메라 이미지 (1~5장)", required = true) List<MultipartFile> images
    );

    @Operation(summary = "SLAM 처리 상태 조회", description = "건물의 SLAM 처리 상태를 조회합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "상태 조회 성공"),
        @ApiResponse(responseCode = "503", description = "VPS 서비스 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<SlamStatusResponse> getSlamStatus(
        @Parameter(description = "건물 ID", required = true) UUID buildingId
    );

    @Operation(summary = "맵 메타데이터 조회", description = "건물의 VPS 맵 메타데이터를 조회합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "메타데이터 조회 성공"),
        @ApiResponse(responseCode = "503", description = "VPS 서비스 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<MapMetadataResponse> getMapMetadata(
        @Parameter(description = "건물 ID", required = true) UUID buildingId
    );

    @Operation(summary = "노드 근처 이미지 조회",
        description = "특정 좌표 근처의 RTAB-Map 카메라 이미지 3장을 반환합니다. 최대한 다른 방향에서 촬영된 이미지를 선택합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "이미지 조회 성공"),
        @ApiResponse(responseCode = "400", description = "잘못된 좌표",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "스캔 세션 또는 이미지 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<List<NodeImageResponse>> findNearbyNodeImages(
        @Parameter(description = "건물 ID", required = true) UUID buildingId,
        NodeImageRequest request
    );
}
