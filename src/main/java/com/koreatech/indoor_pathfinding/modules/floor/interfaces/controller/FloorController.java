package com.koreatech.indoor_pathfinding.modules.floor.interfaces.controller;

import com.koreatech.indoor_pathfinding.modules.floor.interfaces.FloorApi;
import com.koreatech.indoor_pathfinding.modules.floor.application.command.FloorCreator;
import com.koreatech.indoor_pathfinding.modules.floor.application.command.FloorDeleter;
import com.koreatech.indoor_pathfinding.modules.floor.application.command.FloorUpdater;
import com.koreatech.indoor_pathfinding.modules.floor.application.dto.request.FloorCreateRequest;
import com.koreatech.indoor_pathfinding.modules.floor.application.dto.request.FloorUpdateRequest;
import com.koreatech.indoor_pathfinding.modules.floor.application.dto.response.FloorPathResponse;
import com.koreatech.indoor_pathfinding.modules.floor.application.dto.response.FloorResponse;
import com.koreatech.indoor_pathfinding.modules.floor.application.query.FloorReader;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class FloorController implements FloorApi {

    private final FloorCreator floorCreator;
    private final FloorUpdater floorUpdater;
    private final FloorDeleter floorDeleter;
    private final FloorReader floorReader;

    @PostMapping("/api/v1/buildings/{buildingId}/floors")
    public ResponseEntity<FloorResponse> addFloor(
            @PathVariable UUID buildingId,
            @Valid @RequestBody FloorCreateRequest request
    ) {
        FloorResponse response = floorCreator.create(buildingId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/api/v1/buildings/{buildingId}/floors")
    public ResponseEntity<List<FloorResponse>> getFloors(@PathVariable UUID buildingId) {
        List<FloorResponse> floors = floorReader.findByBuildingId(buildingId);
        return ResponseEntity.ok(floors);
    }

    @GetMapping("/api/v1/floors/{floorId}")
    public ResponseEntity<FloorResponse> getFloor(@PathVariable UUID floorId) {
        FloorResponse response = floorReader.findById(floorId);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/api/v1/floors/{floorId}")
    public ResponseEntity<FloorResponse> updateFloor(
            @PathVariable UUID floorId,
            @Valid @RequestBody FloorUpdateRequest request) {
        FloorResponse response = floorUpdater.update(floorId, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/api/v1/floors/{floorId}")
    public ResponseEntity<Void> deleteFloor(@PathVariable UUID floorId) {
        floorDeleter.delete(floorId);
        return ResponseEntity.noContent().build();
    }

    // Floor path endpoint
    @GetMapping("/api/v1/floors/{floorId}/path")
    public ResponseEntity<FloorPathResponse> getFloorPath(@PathVariable UUID floorId) {
        FloorPathResponse response = floorReader.findPathByFloorId(floorId);
        return ResponseEntity.ok(response);
    }
}
