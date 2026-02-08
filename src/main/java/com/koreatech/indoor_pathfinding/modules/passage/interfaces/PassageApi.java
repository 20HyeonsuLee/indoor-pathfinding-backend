package com.koreatech.indoor_pathfinding.modules.passage.interfaces;

import com.koreatech.indoor_pathfinding.modules.passage.application.dto.response.VerticalPassageDetailResponse;
import com.koreatech.indoor_pathfinding.modules.passage.application.dto.response.VerticalPassageResponse;
import com.koreatech.indoor_pathfinding.modules.passage.domain.model.PassageType;
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

@Tag(name = "Passage", description = "수직 통로 API")
public interface PassageApi {

    @Operation(summary = "수직 통로 목록 조회", description = "건물의 수직 통로(계단/엘리베이터) 목록을 조회합니다. 타입별 필터링이 가능합니다.")
    @ApiResponse(responseCode = "200", description = "수직 통로 목록 조회 성공")
    ResponseEntity<List<VerticalPassageResponse>> getVerticalPassages(
        @Parameter(description = "건물 ID", required = true) UUID buildingId,
        @Parameter(description = "통로 타입 필터 (STAIRCASE, ELEVATOR)") PassageType type
    );

    @Operation(summary = "수직 통로 상세 조회", description = "수직 통로의 상세 정보를 조회합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "수직 통로 상세 조회 성공"),
        @ApiResponse(responseCode = "404", description = "통로를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<VerticalPassageDetailResponse> getVerticalPassageDetail(
        @Parameter(description = "통로 ID", required = true) UUID passageId
    );
}
