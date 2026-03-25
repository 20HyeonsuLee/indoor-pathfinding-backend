package com.koreatech.indoor_pathfinding.modules.scan.application.command;

import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;
import com.koreatech.indoor_pathfinding.modules.floor.domain.repository.FloorRepository;
import com.koreatech.indoor_pathfinding.modules.scan.application.dto.response.MergedScanResponse;
import com.koreatech.indoor_pathfinding.modules.scan.domain.event.MergedScanCreatedEvent;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ChunkMerger {

    private final FloorRepository floorRepository;
    private final ScanChunkRepository scanChunkRepository;
    private final MergedScanRepository mergedScanRepository;
    private final ApplicationEventPublisher eventPublisher;

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

        // 트랜잭션 커밋 후 이벤트로 비동기 처리 시작
        eventPublisher.publishEvent(
            MergedScanCreatedEvent.singleChunk(saved.getId(), floor.getId()));

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

        // 트랜잭션 커밋 후 이벤트로 비동기 병합 + 처리 시작
        eventPublisher.publishEvent(
            MergedScanCreatedEvent.multiChunk(saved.getId(), floor.getId(), chunkPaths, outputPath));

        log.info("Multi-chunk merge initiated (floor={}, chunks={})", floor.getId(), chunks.size());
        return MergedScanResponse.from(saved);
    }
}
