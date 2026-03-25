package com.koreatech.indoor_pathfinding.modules.scan.application.command;

import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ScanChunk;
import com.koreatech.indoor_pathfinding.modules.scan.domain.repository.ScanChunkRepository;
import com.koreatech.indoor_pathfinding.shared.exception.BusinessException;
import com.koreatech.indoor_pathfinding.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ChunkDeleter {

    private final ScanChunkRepository scanChunkRepository;

    public void delete(final UUID chunkId) {
        final ScanChunk chunk = scanChunkRepository.findById(chunkId)
            .orElseThrow(() -> new BusinessException(ErrorCode.SCAN_CHUNK_NOT_FOUND));

        deleteFile(chunk.getFilePath());
        scanChunkRepository.delete(chunk);

        log.info("Chunk deleted: {} (floor={})", chunk.getFileName(), chunk.getFloor().getId());
    }

    private void deleteFile(final String filePath) {
        if (filePath == null) return;
        try {
            final Path path = Path.of(filePath);
            if (Files.exists(path)) {
                Files.delete(path);
            }
        } catch (IOException e) {
            log.warn("Failed to delete chunk file {}: {}", filePath, e.getMessage());
        }
    }
}
