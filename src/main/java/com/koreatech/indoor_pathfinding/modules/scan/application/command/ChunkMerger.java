package com.koreatech.indoor_pathfinding.modules.scan.application.command;

import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;
import com.koreatech.indoor_pathfinding.modules.floor.domain.repository.FloorRepository;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.application.command.ProcessingResultApplier;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.application.dto.response.ProcessingStatusResponse;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.infrastructure.external.PathProcessingClient;
import com.koreatech.indoor_pathfinding.modules.scan.application.dto.response.MergedScanResponse;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.MergedScan;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.MergedScanStatus;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ScanChunk;
import com.koreatech.indoor_pathfinding.modules.scan.domain.repository.MergedScanRepository;
import com.koreatech.indoor_pathfinding.modules.scan.domain.repository.ScanChunkRepository;
import com.koreatech.indoor_pathfinding.shared.exception.BusinessException;
import com.koreatech.indoor_pathfinding.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ChunkMerger {

    private final FloorRepository floorRepository;
    private final ScanChunkRepository scanChunkRepository;
    private final MergedScanRepository mergedScanRepository;
    private final PathProcessingClient pathProcessingClient;
    private final ProcessingResultApplier processingResultApplier;

    @Value("${storage.uploads-path:./storage/uploads}")
    private String uploadsPath;

    public MergedScanResponse merge(final UUID floorId, final List<UUID> chunkIds) {
        final Floor floor = floorRepository.findById(floorId)
            .orElseThrow(() -> new BusinessException(ErrorCode.FLOOR_NOT_FOUND));

        final List<ScanChunk> selectedChunks = chunkIds.stream()
            .map(id -> scanChunkRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.SCAN_CHUNK_NOT_FOUND,
                    "Chunk not found: " + id)))
            .toList();

        for (final ScanChunk chunk : selectedChunks) {
            if (!chunk.getFloor().getId().equals(floorId)) {
                throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE,
                    "Chunk " + chunk.getId() + " does not belong to floor " + floorId);
            }
        }

        // 기존 MergedScan 삭제
        mergedScanRepository.findByFloorId(floorId)
            .ifPresent(existing -> {
                floor.updateMergedScan(null);
                mergedScanRepository.delete(existing);
                mergedScanRepository.flush();
            });

        final String sourceChunkIds = selectedChunks.stream()
            .map(chunk -> chunk.getId().toString())
            .collect(Collectors.joining(",", "[", "]"));

        if (selectedChunks.size() == 1) {
            return createFromSingleChunk(floor, selectedChunks.getFirst(), sourceChunkIds);
        }

        return createForMerge(floor, selectedChunks, sourceChunkIds);
    }

    private MergedScanResponse createFromSingleChunk(
            final Floor floor, final ScanChunk chunk, final String sourceChunkIds) {
        final MergedScan mergedScan = MergedScan.builder()
            .filePath(chunk.getFilePath())
            .sourceChunkIds(sourceChunkIds)
            .status(MergedScanStatus.MERGED)
            .build();

        floor.updateMergedScan(mergedScan);
        final MergedScan saved = mergedScanRepository.save(mergedScan);

        log.info("Single chunk — merge skipped (floor={})", floor.getId());

        // 자동으로 처리 + PLY 추출
        CompletableFuture.runAsync(() ->
            processAndExtractPly(saved.getId(), floor.getId()));

        return MergedScanResponse.from(saved);
    }

    private MergedScanResponse createForMerge(
            final Floor floor, final List<ScanChunk> chunks, final String sourceChunkIds) {

        final String outputPath = uploadsPath + "/merged_" + floor.getId() + ".db";

        final MergedScan mergedScan = MergedScan.builder()
            .filePath(outputPath)
            .sourceChunkIds(sourceChunkIds)
            .status(MergedScanStatus.MERGING)
            .build();

        floor.updateMergedScan(mergedScan);
        final MergedScan saved = mergedScanRepository.save(mergedScan);

        final List<String> chunkPaths = chunks.stream()
            .map(ScanChunk::getFilePath)
            .toList();

        final UUID mergedScanId = saved.getId();
        final UUID floorId = floor.getId();
        CompletableFuture.runAsync(() ->
            mergeInBackground(mergedScanId, floorId, chunkPaths, outputPath));

        log.info("Multi-chunk merge initiated (floor={}, chunks={})", floorId, chunks.size());
        return MergedScanResponse.from(saved);
    }

    /**
     * 다중 청크 병합 → 성공 시 자동으로 처리 + PLY 추출
     */
    private void mergeInBackground(final UUID mergedScanId, final UUID floorId,
                                   final List<String> chunkPaths, final String outputPath) {
        try {
            final String mergeJobId = pathProcessingClient.mergeChunks(chunkPaths, outputPath);

            for (int i = 0; i < 60; i++) {
                Thread.sleep(30_000);
                final Map<String, Object> status = pathProcessingClient.getMergeStatus(mergeJobId);
                final String statusStr = (String) status.get("status");

                if ("COMPLETED".equals(statusStr)) {
                    final MergedScan mergedScan = mergedScanRepository.findById(mergedScanId).orElse(null);
                    if (mergedScan != null) {
                        mergedScan.markMerged(outputPath);
                        mergedScanRepository.save(mergedScan);
                    }
                    log.info("Merge completed, starting processing for floor {}", floorId);
                    processAndExtractPly(mergedScanId, floorId);
                    return;
                }

                if ("FAILED".equals(statusStr)) {
                    final String error = (String) status.get("error");
                    updateMergedScanFailed(mergedScanId, error);
                    return;
                }
            }

            updateMergedScanFailed(mergedScanId, "Merge timeout (30 minutes)");

        } catch (Exception e) {
            log.error("Merge background task failed: {}", e.getMessage(), e);
            updateMergedScanFailed(mergedScanId, e.getMessage());
        }
    }

    /**
     * 병합된 .db에서 궤적 추출 + PLY 생성 (기존 PLY 덮어쓰기)
     */
    private void processAndExtractPly(final UUID mergedScanId, final UUID floorId) {
        try {
            final MergedScan mergedScan = mergedScanRepository.findById(mergedScanId).orElse(null);
            if (mergedScan == null) return;

            // 1. Python에 .db 업로드 + 처리 시작
            final String fileId = pathProcessingClient.uploadFile(Paths.get(mergedScan.getFilePath()));
            final String jobId = pathProcessingClient.startProcessing(fileId);

            mergedScan.startProcessing();
            mergedScanRepository.save(mergedScan);

            // 2. 처리 완료 대기
            if (!waitForProcessing(jobId)) {
                updateMergedScanFailed(mergedScanId, "Processing failed or timed out");
                return;
            }

            // 3. 결과 적용 (FloorPath 갱신)
            processingResultApplier.applyToFloor(floorId, jobId);

            // 4. PLY 추출 (기존 PLY 덮어쓰기)
            final String plyKey = pathProcessingClient.extractPointcloudPly(fileId);
            processingResultApplier.updateFloorPly(floorId, plyKey);

            log.info("Floor {} processing + PLY extraction completed (ply={})", floorId, plyKey);

        } catch (Exception e) {
            log.error("Processing/PLY extraction failed for floor {}: {}", floorId, e.getMessage(), e);
            updateMergedScanFailed(mergedScanId, e.getMessage());
        }
    }

    private boolean waitForProcessing(final String jobId) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            Thread.sleep(10_000);
            final ProcessingStatusResponse status = pathProcessingClient.getJobStatus(jobId);

            if ("COMPLETED".equals(status.status())) return true;
            if ("FAILED".equals(status.status())) {
                log.error("Processing job {} failed: {}", jobId, status.error());
                return false;
            }
        }
        log.warn("Processing job {} timeout", jobId);
        return false;
    }

    private void updateMergedScanFailed(final UUID mergedScanId, final String errorMessage) {
        final MergedScan mergedScan = mergedScanRepository.findById(mergedScanId).orElse(null);
        if (mergedScan != null) {
            mergedScan.markFailed(errorMessage);
            mergedScanRepository.save(mergedScan);
        }
    }
}
