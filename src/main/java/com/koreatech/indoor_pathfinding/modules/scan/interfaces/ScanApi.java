package com.koreatech.indoor_pathfinding.modules.scan.interfaces;

import com.koreatech.indoor_pathfinding.modules.scan.application.dto.response.MergedScanResponse;
import com.koreatech.indoor_pathfinding.modules.scan.application.dto.response.ScanChunkResponse;
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

@Tag(name = "Scan", description = "층별 스캔 청크 관리 API")
public interface ScanApi {

    @Operation(summary = "청크 업로드", description = "층에 RTAB-Map .db 청크 파일을 업로드합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "업로드 성공"),
        @ApiResponse(responseCode = "400", description = "잘못된 파일 형식",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "층을 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<ScanChunkResponse> uploadChunk(
        @Parameter(description = "층 ID", required = true) UUID floorId,
        @Parameter(description = "스캔 .db 파일", required = true) MultipartFile file
    );

    @Operation(summary = "청크 목록 조회", description = "층의 모든 스캔 청크 목록을 조회합니다.")
    @ApiResponse(responseCode = "200", description = "청크 목록 조회 성공")
    ResponseEntity<List<ScanChunkResponse>> getChunks(
        @Parameter(description = "층 ID", required = true) UUID floorId
    );

    @Operation(summary = "청크 삭제")
    @ApiResponse(responseCode = "204", description = "삭제 성공")
    ResponseEntity<Void> deleteChunk(
        @Parameter(description = "층 ID") UUID floorId,
        @Parameter(description = "청크 ID") UUID chunkId
    );

    @Operation(summary = "병합 트리거", description = "현재 active 청크들을 병합합니다. 단일 청크면 병합을 스킵합니다.")
    @ApiResponse(responseCode = "200", description = "병합 시작/완료")
    ResponseEntity<MergedScanResponse> merge(
        @Parameter(description = "층 ID", required = true) UUID floorId
    );

    @Operation(summary = "병합 상태 조회")
    @ApiResponse(responseCode = "200", description = "병합 상태 조회 성공")
    ResponseEntity<MergedScanResponse> getMergeStatus(
        @Parameter(description = "층 ID", required = true) UUID floorId
    );
}
