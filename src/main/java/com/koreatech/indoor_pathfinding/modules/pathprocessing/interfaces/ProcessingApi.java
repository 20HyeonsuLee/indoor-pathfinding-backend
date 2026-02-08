package com.koreatech.indoor_pathfinding.modules.pathprocessing.interfaces;

import com.koreatech.indoor_pathfinding.modules.pathprocessing.application.dto.request.ProcessingStartRequest;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.application.dto.response.ProcessingStartResponse;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.application.dto.response.ProcessingStatusResponse;
import com.koreatech.indoor_pathfinding.shared.exception.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

@Tag(name = "Processing", description = "경로 데이터 처리 API")
public interface ProcessingApi {

    @Operation(summary = "경로 처리 시작", description = "스캔 세션의 DB 파일을 Python 서비스로 전송하여 경로 데이터 처리를 시작합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "처리 시작 성공"),
        @ApiResponse(responseCode = "404", description = "건물 또는 세션을 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<ProcessingStartResponse> startProcessing(
        @Parameter(description = "건물 ID", required = true) UUID buildingId,
        ProcessingStartRequest request
    );

    @Operation(summary = "처리 상태 조회", description = "경로 처리 작업의 진행 상태를 조회합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "상태 조회 성공"),
        @ApiResponse(responseCode = "404", description = "건물을 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<ProcessingStatusResponse> getProcessingStatus(
        @Parameter(description = "건물 ID", required = true) UUID buildingId,
        @Parameter(description = "스캔 세션 ID", required = true) UUID sessionId
    );

    @Operation(summary = "처리 결과 적용", description = "경로 처리 결과를 건물 데이터에 적용합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "결과 적용 성공"),
        @ApiResponse(responseCode = "404", description = "건물을 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<Map<String, String>> applyProcessingResult(
        @Parameter(description = "건물 ID", required = true) UUID buildingId,
        ProcessingStartRequest request
    );

    @Operation(summary = "미리보기 이미지 조회", description = "처리 결과의 미리보기 이미지를 조회합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "이미지 조회 성공",
            content = @Content(mediaType = "image/png")),
        @ApiResponse(responseCode = "404", description = "이미지를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<byte[]> getPreviewImage(
        @Parameter(description = "건물 ID", required = true) UUID buildingId,
        @Parameter(description = "작업 ID", required = true) String jobId,
        @Parameter(description = "이미지 타입 (raw, processed, comparison)", required = true) String imageType
    );
}
