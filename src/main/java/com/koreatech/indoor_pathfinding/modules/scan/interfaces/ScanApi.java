package com.koreatech.indoor_pathfinding.modules.scan.interfaces;

import com.koreatech.indoor_pathfinding.modules.scan.application.dto.response.ScanSessionResponse;
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

@Tag(name = "Scan", description = "스캔 데이터 업로드 API")
public interface ScanApi {

    @Operation(summary = "스캔 파일 업로드", description = "RTAB-Map으로 스캔한 .db 파일을 업로드합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "업로드 성공"),
        @ApiResponse(responseCode = "400", description = "잘못된 파일 형식",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "건물을 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<ScanSessionResponse> uploadScanFile(
        @Parameter(description = "건물 ID", required = true) UUID buildingId,
        @Parameter(description = "스캔 .db 파일", required = true) MultipartFile file
    );

    @Operation(summary = "스캔 세션 목록 조회", description = "건물의 모든 스캔 세션 목록을 조회합니다.")
    @ApiResponse(responseCode = "200", description = "세션 목록 조회 성공")
    ResponseEntity<List<ScanSessionResponse>> getScanSessions(
        @Parameter(description = "건물 ID", required = true) UUID buildingId
    );

    @Operation(summary = "스캔 세션 상세 조회", description = "특정 스캔 세션의 상세 정보를 조회합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "세션 조회 성공"),
        @ApiResponse(responseCode = "404", description = "세션을 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<ScanSessionResponse> getScanSession(
        @Parameter(description = "건물 ID", required = true) UUID buildingId,
        @Parameter(description = "세션 ID", required = true) UUID sessionId
    );
}
