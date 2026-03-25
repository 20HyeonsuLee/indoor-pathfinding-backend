package com.koreatech.indoor_pathfinding.modules.localization.application.service;

import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;
import com.koreatech.indoor_pathfinding.modules.floor.domain.repository.FloorRepository;
import com.koreatech.indoor_pathfinding.modules.localization.application.dto.response.NodeImageResponse;
import com.koreatech.indoor_pathfinding.modules.localization.infrastructure.persistence.RtabMapImageExtractor;
import com.koreatech.indoor_pathfinding.modules.localization.infrastructure.persistence.RtabMapImageExtractor.NearbyNodeImage;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.MergedScan;
import com.koreatech.indoor_pathfinding.modules.scan.domain.repository.MergedScanRepository;
import com.koreatech.indoor_pathfinding.shared.exception.BusinessException;
import com.koreatech.indoor_pathfinding.shared.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class NodeImageService {

    private static final int DEFAULT_IMAGE_COUNT = 3;

    private final FloorRepository floorRepository;
    private final MergedScanRepository mergedScanRepository;
    private final RtabMapImageExtractor imageExtractor;
    private final String imagesPath;

    public NodeImageService(
            final FloorRepository floorRepository,
            final MergedScanRepository mergedScanRepository,
            final RtabMapImageExtractor imageExtractor,
            @Value("${storage.images-path:./storage/images}") final String imagesPath
    ) {
        this.floorRepository = floorRepository;
        this.mergedScanRepository = mergedScanRepository;
        this.imageExtractor = imageExtractor;
        this.imagesPath = imagesPath;
    }

    public List<NodeImageResponse> findNearbyImages(
            final UUID buildingId,
            final double x,
            final double y,
            final double z
    ) {
        // 건물의 모든 층에서 MergedScan이 있는 것을 찾아 매칭
        final List<Floor> floors = floorRepository.findByBuildingIdOrderByLevelAsc(buildingId);

        for (final Floor floor : floors) {
            final var mergedScanOpt = mergedScanRepository.findByFloorId(floor.getId());
            if (mergedScanOpt.isEmpty()) continue;

            final MergedScan mergedScan = mergedScanOpt.get();
            if (mergedScan.getFilePath() == null) continue;

            final Path dbPath = Paths.get(mergedScan.getFilePath());
            final List<NearbyNodeImage> images = imageExtractor.extractNearbyImages(
                dbPath, x, y, z, DEFAULT_IMAGE_COUNT
            );

            if (!images.isEmpty()) {
                return images.stream()
                    .map(image -> saveAndCreateResponse(buildingId, image))
                    .toList();
            }
        }

        throw new BusinessException(ErrorCode.ENTITY_NOT_FOUND,
            "No images found near the specified position");
    }

    private NodeImageResponse saveAndCreateResponse(final UUID buildingId, final NearbyNodeImage image) {
        final String imageUrl = saveImageFile(buildingId, image);
        return NodeImageResponse.of(image, imageUrl);
    }

    private String saveImageFile(final UUID buildingId, final NearbyNodeImage image) {
        final Path directory = Paths.get(imagesPath, buildingId.toString());
        final String fileName = image.nodeId() + ".jpg";

        try {
            Files.createDirectories(directory);
            Files.write(directory.resolve(fileName), image.imageData());
        } catch (IOException exception) {
            log.error("Failed to save node image: {}/{}", buildingId, fileName, exception);
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR,
                "Failed to save node image");
        }

        return "/images/" + buildingId + "/" + fileName;
    }
}
