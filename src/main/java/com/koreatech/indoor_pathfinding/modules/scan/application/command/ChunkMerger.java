package com.koreatech.indoor_pathfinding.modules.scan.application.command;

import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;
import com.koreatech.indoor_pathfinding.modules.floor.domain.repository.FloorRepository;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    @Value("${storage.uploads-path:./storage/uploads}")
    private String uploadsPath;

    public MergedScanResponse merge(final UUID floorId) {
        final Floor floor = floorRepository.findById(floorId)
            .orElseThrow(() -> new BusinessException(ErrorCode.FLOOR_NOT_FOUND));

        final List<ScanChunk> activeChunks =
            scanChunkRepository.findByFloorIdAndActiveOrderByUploadOrderAsc(floorId, true);

        if (activeChunks.isEmpty()) {
            throw new BusinessException(ErrorCode.NO_ACTIVE_CHUNKS);
        }

        // 기존 MergedScan 삭제
        mergedScanRepository.findByFloorId(floorId)
            .ifPresent(existing -> {
                floor.updateMergedScan(null);
                mergedScanRepository.delete(existing);
                mergedScanRepository.flush();
            });

        final String sourceChunkIds = activeChunks.stream()
            .map(chunk -> chunk.getId().toString())
            .collect(Collectors.joining(",", "[", "]"));

        // 단일 청크인 경우 병합 스킵
        if (activeChunks.size() == 1) {
            return createMergedScanFromSingleChunk(floor, activeChunks.getFirst(), sourceChunkIds);
        }

        // 다중 청크 병합 (비동기 처리 — 추후 Python 서비스 연동)
        return createMergedScanForMerge(floor, sourceChunkIds);
    }

    private MergedScanResponse createMergedScanFromSingleChunk(
            final Floor floor, final ScanChunk chunk, final String sourceChunkIds) {
        final MergedScan mergedScan = MergedScan.builder()
            .filePath(chunk.getFilePath())
            .sourceChunkIds(sourceChunkIds)
            .status(MergedScanStatus.MERGED)
            .build();

        floor.updateMergedScan(mergedScan);
        final MergedScan saved = mergedScanRepository.save(mergedScan);

        log.info("Single chunk — merge skipped (floor={})", floor.getId());
        return MergedScanResponse.from(saved);
    }

    private MergedScanResponse createMergedScanForMerge(
            final Floor floor, final String sourceChunkIds) {
        final MergedScan mergedScan = MergedScan.builder()
            .sourceChunkIds(sourceChunkIds)
            .status(MergedScanStatus.MERGING)
            .build();

        floor.updateMergedScan(mergedScan);
        final MergedScan saved = mergedScanRepository.save(mergedScan);

        // TODO: Python 서비스에 rtabmap-reprocess 호출 (비동기)
        // 현재는 MERGING 상태로 생성만 하고, 추후 Python merge 엔드포인트 연동
        log.info("Multi-chunk merge initiated (floor={}, chunks={})", floor.getId(), sourceChunkIds);

        return MergedScanResponse.from(saved);
    }
}
