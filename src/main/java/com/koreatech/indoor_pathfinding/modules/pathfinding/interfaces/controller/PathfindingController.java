package com.koreatech.indoor_pathfinding.modules.pathfinding.interfaces.controller;

import com.koreatech.indoor_pathfinding.modules.pathfinding.interfaces.PathfindingApi;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.request.PathfindingRequest;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.response.PathfindingResponse;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.service.PathfindingService;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.PathPreference;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/buildings/{buildingId}/pathfinding")
@RequiredArgsConstructor
public class PathfindingController implements PathfindingApi {

    private final PathfindingService pathfindingService;

    @PostMapping
    public ResponseEntity<PathfindingResponse> findPath(
            @PathVariable UUID buildingId,
            @Valid @RequestBody PathfindingRequest request) {
        PathfindingResponse response = pathfindingService.findPath(buildingId, request);
        return ResponseEntity.ok(response);
    }
}
