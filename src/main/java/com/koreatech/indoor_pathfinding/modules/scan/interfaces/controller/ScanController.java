package com.koreatech.indoor_pathfinding.modules.scan.interfaces.controller;

import com.koreatech.indoor_pathfinding.modules.scan.interfaces.ScanApi;
import com.koreatech.indoor_pathfinding.modules.scan.application.command.ChunkDeleter;
import com.koreatech.indoor_pathfinding.modules.scan.application.command.ChunkMerger;
import com.koreatech.indoor_pathfinding.modules.scan.application.command.ChunkUploader;
import com.koreatech.indoor_pathfinding.modules.scan.application.dto.request.MergeRequest;
import com.koreatech.indoor_pathfinding.modules.scan.application.dto.response.MergedScanResponse;
import com.koreatech.indoor_pathfinding.modules.scan.application.dto.response.ScanChunkResponse;
import com.koreatech.indoor_pathfinding.modules.scan.application.query.MergedScanReader;
import com.koreatech.indoor_pathfinding.modules.scan.application.query.ScanChunkReader;
import jakarta.validation.Valid;
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
@RequestMapping("/api/v1/floors/{floorId}/scans")
@RequiredArgsConstructor
public class ScanController implements ScanApi {

    private final ChunkUploader chunkUploader;
    private final ChunkDeleter chunkDeleter;
    private final ChunkMerger chunkMerger;
    private final ScanChunkReader scanChunkReader;
    private final MergedScanReader mergedScanReader;

    @PostMapping("/chunks")
    public ResponseEntity<ScanChunkResponse> uploadChunk(
            @PathVariable final UUID floorId,
            @RequestParam("file") final MultipartFile file) {
        final ScanChunkResponse response = chunkUploader.upload(floorId, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/chunks")
    public ResponseEntity<List<ScanChunkResponse>> getChunks(@PathVariable final UUID floorId) {
        final List<ScanChunkResponse> chunks = scanChunkReader.findByFloorId(floorId);
        return ResponseEntity.ok(chunks);
    }

    @DeleteMapping("/chunks/{chunkId}")
    public ResponseEntity<Void> deleteChunk(
            @PathVariable final UUID floorId,
            @PathVariable final UUID chunkId) {
        chunkDeleter.delete(chunkId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/merge")
    public ResponseEntity<MergedScanResponse> merge(
            @PathVariable final UUID floorId,
            @Valid @RequestBody final MergeRequest request) {
        final MergedScanResponse response = chunkMerger.merge(floorId, request.chunkIds());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/merge/status")
    public ResponseEntity<MergedScanResponse> getMergeStatus(@PathVariable final UUID floorId) {
        final MergedScanResponse response = mergedScanReader.findByFloorId(floorId);
        if (response == null) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.ok(response);
    }
}
