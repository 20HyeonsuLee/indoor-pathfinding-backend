package com.koreatech.indoor_pathfinding.modules.pathprocessing.interfaces.controller;

import com.koreatech.indoor_pathfinding.modules.pathprocessing.interfaces.ProcessingApi;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.application.command.ProcessingResultApplier;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.application.command.ProcessingStarter;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.application.dto.request.ProcessingStartRequest;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.application.dto.response.ProcessingStartResponse;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.application.dto.response.ProcessingStatusResponse;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.application.query.PreviewImageReader;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.application.query.ProcessingStatusReader;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/buildings/{buildingId}")
@RequiredArgsConstructor
public class ProcessingController implements ProcessingApi {

    private final ProcessingStarter processingStarter;
    private final ProcessingResultApplier processingResultApplier;
    private final ProcessingStatusReader processingStatusReader;
    private final PreviewImageReader previewImageReader;

    @PostMapping("/process")
    public ResponseEntity<ProcessingStartResponse> startProcessing(
            @PathVariable UUID buildingId,
            @Valid @RequestBody ProcessingStartRequest request
    ) {
        ProcessingStartResponse response = processingStarter.start(buildingId, request.sessionId());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/process/status")
    public ResponseEntity<ProcessingStatusResponse> getProcessingStatus(
            @PathVariable UUID buildingId,
            @RequestParam UUID sessionId
    ) {
        ProcessingStatusResponse status = processingStatusReader.getStatus(buildingId, sessionId);
        return ResponseEntity.ok(status);
    }

    @PostMapping("/process/apply")
    public ResponseEntity<Map<String, String>> applyProcessingResult(
            @PathVariable UUID buildingId,
            @Valid @RequestBody ProcessingStartRequest request
    ) {
        processingResultApplier.apply(request.sessionId());

        return ResponseEntity.ok(Map.of(
            "status", "success",
            "message", "Processing result applied successfully"
        ));
    }

    @GetMapping("/preview/{jobId}/{imageType}")
    public ResponseEntity<byte[]> getPreviewImage(
            @PathVariable UUID buildingId,
            @PathVariable String jobId,
            @PathVariable String imageType
    ) {
        byte[] image = previewImageReader.getPreviewImage(jobId, imageType);
        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_PNG)
            .body(image);
    }
}
