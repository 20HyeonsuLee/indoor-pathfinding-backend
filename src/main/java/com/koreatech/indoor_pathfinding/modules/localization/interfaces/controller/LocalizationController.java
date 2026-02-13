package com.koreatech.indoor_pathfinding.modules.localization.interfaces.controller;

import com.koreatech.indoor_pathfinding.modules.localization.application.dto.request.NodeImageRequest;
import com.koreatech.indoor_pathfinding.modules.localization.application.dto.response.LocalizeResponse;
import com.koreatech.indoor_pathfinding.modules.localization.application.dto.response.MapMetadataResponse;
import com.koreatech.indoor_pathfinding.modules.localization.application.dto.response.NodeImageResponse;
import com.koreatech.indoor_pathfinding.modules.localization.application.dto.response.SlamStatusResponse;
import com.koreatech.indoor_pathfinding.modules.localization.application.service.LocalizationService;
import com.koreatech.indoor_pathfinding.modules.localization.application.service.NodeImageService;
import com.koreatech.indoor_pathfinding.modules.localization.interfaces.LocalizationApi;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/buildings/{buildingId}")
@RequiredArgsConstructor
public class LocalizationController implements LocalizationApi {

    private final LocalizationService localizationService;
    private final NodeImageService nodeImageService;

    @Override
    @PostMapping("/localize")
    public ResponseEntity<LocalizeResponse> localize(
            @PathVariable final UUID buildingId,
            @RequestParam("images") final List<MultipartFile> images) {
        final LocalizeResponse response = localizationService.localize(buildingId, images);
        return ResponseEntity.ok(response);
    }

    @Override
    @GetMapping("/slam/status")
    public ResponseEntity<SlamStatusResponse> getSlamStatus(
            @PathVariable final UUID buildingId) {
        final SlamStatusResponse response = localizationService.getSlamStatus(buildingId);
        return ResponseEntity.ok(response);
    }

    @Override
    @GetMapping("/slam/metadata")
    public ResponseEntity<MapMetadataResponse> getMapMetadata(
            @PathVariable final UUID buildingId) {
        final MapMetadataResponse response = localizationService.getMapMetadata(buildingId);
        return ResponseEntity.ok(response);
    }

    @Override
    @PostMapping("/node-images")
    public ResponseEntity<List<NodeImageResponse>> findNearbyNodeImages(
            @PathVariable final UUID buildingId,
            @Valid @RequestBody final NodeImageRequest request) {
        final List<NodeImageResponse> response = nodeImageService.findNearbyImages(
            buildingId, request.x(), request.y(), request.z()
        );
        return ResponseEntity.ok(response);
    }

}
