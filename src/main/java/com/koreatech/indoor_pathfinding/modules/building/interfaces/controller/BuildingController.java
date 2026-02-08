package com.koreatech.indoor_pathfinding.modules.building.interfaces.controller;

import com.koreatech.indoor_pathfinding.modules.building.interfaces.BuildingApi;
import com.koreatech.indoor_pathfinding.modules.building.application.command.BuildingCreator;
import com.koreatech.indoor_pathfinding.modules.building.application.command.BuildingDeleter;
import com.koreatech.indoor_pathfinding.modules.building.application.command.BuildingUpdater;
import com.koreatech.indoor_pathfinding.modules.building.application.dto.request.BuildingCreateRequest;
import com.koreatech.indoor_pathfinding.modules.building.application.dto.request.BuildingStatusUpdateRequest;
import com.koreatech.indoor_pathfinding.modules.building.application.dto.request.BuildingUpdateRequest;
import com.koreatech.indoor_pathfinding.modules.building.application.dto.response.BuildingDetailResponse;
import com.koreatech.indoor_pathfinding.modules.building.application.dto.response.BuildingResponse;
import com.koreatech.indoor_pathfinding.modules.building.application.query.BuildingReader;
import com.koreatech.indoor_pathfinding.modules.building.domain.model.BuildingStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/buildings")
@RequiredArgsConstructor
public class BuildingController implements BuildingApi {

    private final BuildingCreator buildingCreator;
    private final BuildingUpdater buildingUpdater;
    private final BuildingDeleter buildingDeleter;
    private final BuildingReader buildingReader;

    @PostMapping
    public ResponseEntity<BuildingResponse> createBuilding(@Valid @RequestBody BuildingCreateRequest request) {
        BuildingResponse response = buildingCreator.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<BuildingResponse>> getAllBuildings(
            @RequestParam(required = false) BuildingStatus status
    ) {
        if (status != null) {
            return ResponseEntity.ok(buildingReader.findByStatus(status));
        }
        return ResponseEntity.ok(buildingReader.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<BuildingDetailResponse> getBuilding(@PathVariable UUID id) {
        BuildingDetailResponse response = buildingReader.findById(id);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<BuildingResponse> updateBuilding(
            @PathVariable UUID id,
            @Valid @RequestBody BuildingUpdateRequest request) {
        BuildingResponse response = buildingUpdater.update(id, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBuilding(@PathVariable UUID id) {
        buildingDeleter.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<BuildingResponse> updateBuildingStatus(
            @PathVariable UUID id,
            @Valid @RequestBody BuildingStatusUpdateRequest request) {
        BuildingResponse response = buildingUpdater.updateStatus(id, request.status());
        return ResponseEntity.ok(response);
    }
}
