package com.koreatech.indoor_pathfinding.modules.pathfinding.interfaces.controller;

import com.koreatech.indoor_pathfinding.modules.pathfinding.interfaces.PoiApi;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.command.PoiManager;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.request.PoiCreateRequest;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.request.PoiRegisterRequest;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.response.PoiResponse;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.query.PoiReader;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PoiController implements PoiApi {

    private final PoiManager poiManager;
    private final PoiReader poiReader;

    @PostMapping("/buildings/{buildingId}/pois")
    public ResponseEntity<PoiResponse> createPoi(
            @PathVariable UUID buildingId,
            @Valid @RequestBody PoiCreateRequest request) {
        PoiResponse response = poiManager.createPoi(buildingId, request);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/nodes/{nodeId}/poi")
    public ResponseEntity<PoiResponse> registerPoi(
            @PathVariable UUID nodeId,
            @Valid @RequestBody PoiRegisterRequest request) {
        PoiResponse response = poiManager.registerPoi(nodeId, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/nodes/{nodeId}/poi")
    public ResponseEntity<Void> deletePoi(@PathVariable UUID nodeId) {
        poiManager.deletePoi(nodeId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/buildings/{buildingId}/pois")
    public ResponseEntity<List<PoiResponse>> getPoiList(@PathVariable UUID buildingId) {
        List<PoiResponse> response = poiReader.findAllByBuildingId(buildingId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/buildings/{buildingId}/pois/search")
    public ResponseEntity<List<PoiResponse>> searchPois(
            @PathVariable UUID buildingId,
            @RequestParam(required = false) String query) {
        List<PoiResponse> response = poiReader.searchByName(buildingId, query);
        return ResponseEntity.ok(response);
    }
}
