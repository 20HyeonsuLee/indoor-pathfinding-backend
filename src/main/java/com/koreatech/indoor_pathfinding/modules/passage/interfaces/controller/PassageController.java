package com.koreatech.indoor_pathfinding.modules.passage.interfaces.controller;

import com.koreatech.indoor_pathfinding.modules.passage.interfaces.PassageApi;
import com.koreatech.indoor_pathfinding.modules.passage.application.dto.response.VerticalPassageDetailResponse;
import com.koreatech.indoor_pathfinding.modules.passage.application.dto.response.VerticalPassageResponse;
import com.koreatech.indoor_pathfinding.modules.passage.application.query.PassageReader;
import com.koreatech.indoor_pathfinding.modules.passage.domain.model.PassageType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class PassageController implements PassageApi {

    private final PassageReader passageReader;

    @GetMapping("/api/v1/buildings/{buildingId}/passages")
    public ResponseEntity<List<VerticalPassageResponse>> getVerticalPassages(
            @PathVariable UUID buildingId,
            @RequestParam(required = false) PassageType type
    ) {
        if (type != null) {
            return ResponseEntity.ok(passageReader.findByBuildingIdAndType(buildingId, type));
        }
        return ResponseEntity.ok(passageReader.findByBuildingId(buildingId));
    }

    @GetMapping("/api/v1/passages/{passageId}")
    public ResponseEntity<VerticalPassageDetailResponse> getVerticalPassageDetail(
            @PathVariable UUID passageId
    ) {
        VerticalPassageDetailResponse response = passageReader.findById(passageId);
        return ResponseEntity.ok(response);
    }
}
