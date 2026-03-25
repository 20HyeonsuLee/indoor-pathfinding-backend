package com.koreatech.indoor_pathfinding.modules.pathprocessing.interfaces;

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

import java.util.UUID;

@Tag(name = "Processing", description = "경로 데이터 처리 API")
public interface ProcessingApi {

    @Operation(summary = "경로 처리 시작", description = "층의 병합된 .db 파일로 경로 추출 처리를 시작합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "처리 시작 성공"),
        @ApiResponse(responseCode = "404", description = "층 또는 병합 스캔을 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<ProcessingStartResponse> startProcessing(
        @Parameter(description = "층 ID", required = true) UUID floorId
    );

    @Operation(summary = "처리 상태 조회")
    @ApiResponse(responseCode = "200", description = "상태 조회 성공")
    ResponseEntity<ProcessingStatusResponse> getProcessingStatus(
        @Parameter(description = "층 ID", required = true) UUID floorId
    );

    @Operation(summary = "미리보기 이미지 조회")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "이미지 조회 성공",
            content = @Content(mediaType = "image/png"))
    })
    ResponseEntity<byte[]> getPreviewImage(
        @Parameter(description = "층 ID") UUID floorId,
        @Parameter(description = "작업 ID") String jobId,
        @Parameter(description = "이미지 타입") String imageType
    );
}
