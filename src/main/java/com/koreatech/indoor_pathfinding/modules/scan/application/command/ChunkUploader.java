package com.koreatech.indoor_pathfinding.modules.scan.application.command;

import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;
import com.koreatech.indoor_pathfinding.modules.floor.domain.repository.FloorRepository;
import com.koreatech.indoor_pathfinding.modules.scan.application.dto.response.ScanChunkResponse;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ChunkStatus;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ScanChunk;
import com.koreatech.indoor_pathfinding.modules.scan.domain.repository.ScanChunkRepository;
import com.koreatech.indoor_pathfinding.shared.exception.BusinessException;
import com.koreatech.indoor_pathfinding.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ChunkUploader {

    private final ScanChunkRepository scanChunkRepository;
    private final FloorRepository floorRepository;

    @Value("${storage.uploads-path:./storage/uploads}")
    private String uploadsPath;

    public ScanChunkResponse upload(final UUID floorId, final MultipartFile file) {
        final Floor floor = floorRepository.findById(floorId)
            .orElseThrow(() -> new BusinessException(ErrorCode.FLOOR_NOT_FOUND));

        validateFile(file);

        try {
            final Path uploadDir = Paths.get(uploadsPath);
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }

            final String savedFilename = UUID.randomUUID() + ".db";
            final Path filePath = uploadDir.resolve(savedFilename);
            Files.write(filePath, file.getBytes());

            final ScanChunk chunk = ScanChunk.builder()
                .fileName(file.getOriginalFilename())
                .filePath(filePath.toString())
                .fileSize(file.getSize())
                .status(ChunkStatus.UPLOADED)
                .active(true)
                .uploadOrder(floor.nextUploadOrder())
                .build();

            floor.addScanChunk(chunk);
            final ScanChunk saved = scanChunkRepository.save(chunk);

            log.info("Chunk uploaded: {} (floor={}, order={})",
                saved.getFileName(), floorId, saved.getUploadOrder());

            return ScanChunkResponse.from(saved);

        } catch (IOException e) {
            log.error("Failed to save chunk file", e);
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR,
                "Failed to save chunk file: " + e.getMessage());
        }
    }

    private void validateFile(final MultipartFile file) {
        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_SCAN_FILE, "File must not be empty");
        }

        final String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.endsWith(".db")) {
            throw new BusinessException(ErrorCode.INVALID_SCAN_FILE, "File must be a .db file");
        }
    }
}
