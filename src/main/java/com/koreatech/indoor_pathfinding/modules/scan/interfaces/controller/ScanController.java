package com.koreatech.indoor_pathfinding.modules.scan.interfaces.controller;

import com.koreatech.indoor_pathfinding.modules.pathprocessing.infrastructure.external.PathProcessingClient;
import com.koreatech.indoor_pathfinding.modules.scan.interfaces.ScanApi;
import com.koreatech.indoor_pathfinding.modules.scan.application.command.ScanFileUploader;
import com.koreatech.indoor_pathfinding.modules.scan.application.dto.response.ScanSessionResponse;
import com.koreatech.indoor_pathfinding.modules.scan.application.query.ScanSessionReader;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ScanSession;
import com.koreatech.indoor_pathfinding.shared.exception.BusinessException;
import com.koreatech.indoor_pathfinding.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
    private final PathProcessingClient pathProcessingClient;

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

    @GetMapping("/{sessionId}/pointcloud")
    public ResponseEntity<byte[]> getPointcloudPly(
            @PathVariable UUID buildingId,
            @PathVariable UUID sessionId) {
        ScanSession session = scanSessionReader.findEntityById(sessionId);

        String cacheKey = session.getPlyFileId();

        // PLY 다운로드 시도, 실패하면 재추출
        byte[] plyData = null;
        if (cacheKey != null) {
            try {
                plyData = pathProcessingClient.getPointcloudPly(cacheKey);
            } catch (Exception e) {
                log.warn("Cached PLY not found ({}), re-extracting...", cacheKey);
                cacheKey = null;
            }
        }

        if (plyData == null) {
            log.info("Extracting PLY on demand for session {}", sessionId);
            // Spring Boot: /app/storage/uploads/xxx.db → Python: /app/uploads/xxx.db
            String springPath = java.nio.file.Paths.get(session.getFilePath()).toAbsolutePath().toString();
            String pythonPath = springPath.replace("/app/storage/uploads/", "/app/uploads/");
            cacheKey = pathProcessingClient.extractPointcloudPly(pythonPath);
            session.updatePlyFileId(cacheKey);
            plyData = pathProcessingClient.getPointcloudPly(cacheKey);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDispositionFormData("attachment", sessionId + ".ply");

        return new ResponseEntity<>(plyData, headers, HttpStatus.OK);
    }
}
