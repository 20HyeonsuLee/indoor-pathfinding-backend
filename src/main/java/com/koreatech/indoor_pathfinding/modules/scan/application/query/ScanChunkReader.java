package com.koreatech.indoor_pathfinding.modules.scan.application.query;

import com.koreatech.indoor_pathfinding.modules.floor.domain.repository.FloorRepository;
import com.koreatech.indoor_pathfinding.modules.scan.application.dto.response.ScanChunkResponse;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ScanChunk;
import com.koreatech.indoor_pathfinding.modules.scan.domain.repository.ScanChunkRepository;
import com.koreatech.indoor_pathfinding.shared.exception.BusinessException;
import com.koreatech.indoor_pathfinding.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScanChunkReader {

    private final ScanChunkRepository scanChunkRepository;
    private final FloorRepository floorRepository;

    public List<ScanChunkResponse> findByFloorId(final UUID floorId) {
        if (!floorRepository.existsById(floorId)) {
            throw new BusinessException(ErrorCode.FLOOR_NOT_FOUND);
        }

        return scanChunkRepository.findByFloorIdOrderByUploadOrderAsc(floorId).stream()
            .map(ScanChunkResponse::from)
            .toList();
    }

    public ScanChunk findEntityById(final UUID chunkId) {
        return scanChunkRepository.findById(chunkId)
            .orElseThrow(() -> new BusinessException(ErrorCode.SCAN_CHUNK_NOT_FOUND));
    }
}
