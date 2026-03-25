package com.koreatech.indoor_pathfinding.modules.pathprocessing.interfaces.controller;

import com.koreatech.indoor_pathfinding.modules.pathprocessing.interfaces.ProcessingApi;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.application.command.ProcessingStarter;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.application.dto.response.ProcessingStartResponse;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.application.dto.response.ProcessingStatusResponse;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.application.query.PreviewImageReader;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.application.query.ProcessingStatusReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/floors/{floorId}")
@RequiredArgsConstructor
public class ProcessingController implements ProcessingApi {

    private final ProcessingStarter processingStarter;
    private final ProcessingStatusReader processingStatusReader;
    private final PreviewImageReader previewImageReader;

    @PostMapping("/process")
    public ResponseEntity<ProcessingStartResponse> startProcessing(
            @PathVariable final UUID floorId) {
        final ProcessingStartResponse response = processingStarter.start(floorId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/process/status")
    public ResponseEntity<ProcessingStatusResponse> getProcessingStatus(
            @PathVariable final UUID floorId) {
        final ProcessingStatusResponse status = processingStatusReader.getStatusByFloor(floorId);
        return ResponseEntity.ok(status);
    }

    @GetMapping("/preview/{jobId}/{imageType}")
    public ResponseEntity<byte[]> getPreviewImage(
            @PathVariable final UUID floorId,
            @PathVariable final String jobId,
            @PathVariable final String imageType) {
        final byte[] image = previewImageReader.getPreviewImage(jobId, imageType);
        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_PNG)
            .body(image);
    }
}
