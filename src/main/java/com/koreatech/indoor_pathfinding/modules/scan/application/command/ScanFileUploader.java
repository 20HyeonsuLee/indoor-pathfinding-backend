package com.koreatech.indoor_pathfinding.modules.scan.application.command;

import com.koreatech.indoor_pathfinding.modules.building.domain.model.Building;
import com.koreatech.indoor_pathfinding.modules.building.domain.repository.BuildingRepository;
import com.koreatech.indoor_pathfinding.modules.scan.application.dto.response.ScanSessionResponse;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ScanSession;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ScanStatus;
import com.koreatech.indoor_pathfinding.modules.scan.domain.repository.ScanSessionRepository;
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
public class ScanFileUploader {

    private final ScanSessionRepository scanSessionRepository;
    private final BuildingRepository buildingRepository;

    @Value("${storage.uploads-path:./storage/uploads}")
    private String uploadsPath;

    public ScanSessionResponse upload(UUID buildingId, MultipartFile file) {
        Building building = buildingRepository.findById(buildingId)
            .orElseThrow(() -> new BusinessException(ErrorCode.BUILDING_NOT_FOUND));

        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_SCAN_FILE,
                "File must not be empty");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.endsWith(".db")) {
            throw new BusinessException(ErrorCode.INVALID_SCAN_FILE,
                "File must be a .db file");
        }

        try {
            Path uploadDir = Paths.get(uploadsPath);
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }

            String fileId = UUID.randomUUID().toString();
            String savedFilename = fileId + ".db";
            Path filePath = uploadDir.resolve(savedFilename);

            Files.write(filePath, file.getBytes());

            ScanSession session = ScanSession.builder()
                .fileName(originalFilename)
                .filePath(filePath.toString())
                .fileSize(file.getSize())
                .status(ScanStatus.UPLOADED)
                .build();

            building.addScanSession(session);
            ScanSession saved = scanSessionRepository.save(session);

            return ScanSessionResponse.from(saved);

        } catch (IOException e) {
            log.error("Failed to save scan file", e);
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR,
                "Failed to save scan file: " + e.getMessage());
        }
    }
}
