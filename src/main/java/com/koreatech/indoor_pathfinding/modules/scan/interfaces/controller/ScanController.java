package com.koreatech.indoor_pathfinding.modules.scan.interfaces.controller;

import com.koreatech.indoor_pathfinding.modules.scan.interfaces.ScanApi;
import com.koreatech.indoor_pathfinding.modules.scan.application.command.ScanFileUploader;
import com.koreatech.indoor_pathfinding.modules.scan.application.dto.response.ScanSessionResponse;
import com.koreatech.indoor_pathfinding.modules.scan.application.query.ScanSessionReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/buildings/{buildingId}/scans")
@RequiredArgsConstructor
public class ScanController implements ScanApi {

    private final ScanFileUploader scanFileUploader;
    private final ScanSessionReader scanSessionReader;

    @PostMapping
    public ResponseEntity<ScanSessionResponse> uploadScanFile(
            @PathVariable UUID buildingId,
            @RequestParam("file") MultipartFile file) {
        ScanSessionResponse response = scanFileUploader.upload(buildingId, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<ScanSessionResponse>> getScanSessions(@PathVariable UUID buildingId) {
        List<ScanSessionResponse> sessions = scanSessionReader.findByBuildingId(buildingId);
        return ResponseEntity.ok(sessions);
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<ScanSessionResponse> getScanSession(
            @PathVariable UUID buildingId,
            @PathVariable UUID sessionId) {
        ScanSessionResponse response = scanSessionReader.findById(sessionId);
        return ResponseEntity.ok(response);
    }
}
