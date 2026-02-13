package com.koreatech.indoor_pathfinding.modules.localization.application.service;

import com.koreatech.indoor_pathfinding.modules.localization.application.dto.response.NodeImageResponse;
import com.koreatech.indoor_pathfinding.modules.localization.infrastructure.persistence.RtabMapImageExtractor;
import com.koreatech.indoor_pathfinding.modules.localization.infrastructure.persistence.RtabMapImageExtractor.NearbyNodeImage;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ScanSession;
import com.koreatech.indoor_pathfinding.modules.scan.domain.repository.ScanSessionRepository;
import com.koreatech.indoor_pathfinding.shared.exception.BusinessException;
import com.koreatech.indoor_pathfinding.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NodeImageService {

    private static final int DEFAULT_IMAGE_COUNT = 3;

    private final ScanSessionRepository scanSessionRepository;
    private final RtabMapImageExtractor imageExtractor;

    public List<NodeImageResponse> findNearbyImages(
            final UUID buildingId,
            final double x,
            final double y,
            final double z
    ) {
        final ScanSession session = findLatestSession(buildingId);
        final Path dbPath = Paths.get(session.getFilePath());

        log.info("Extracting nearby images from {} for position ({}, {}, {})", dbPath, x, y, z);

        final List<NearbyNodeImage> images = imageExtractor.extractNearbyImages(
            dbPath, x, y, z, DEFAULT_IMAGE_COUNT
        );

        if (images.isEmpty()) {
            throw new BusinessException(ErrorCode.ENTITY_NOT_FOUND,
                "No images found near the specified position");
        }

        return images.stream()
            .map(NodeImageResponse::from)
            .toList();
    }

    private ScanSession findLatestSession(final UUID buildingId) {
        return scanSessionRepository.findFirstByBuildingIdOrderByCreatedAtDesc(buildingId)
            .orElseThrow(() -> new BusinessException(ErrorCode.SCAN_SESSION_NOT_FOUND,
                "No scan session found for building: " + buildingId));
    }
}
