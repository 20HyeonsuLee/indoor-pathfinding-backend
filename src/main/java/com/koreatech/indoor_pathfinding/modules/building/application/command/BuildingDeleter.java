package com.koreatech.indoor_pathfinding.modules.building.application.command;

import com.koreatech.indoor_pathfinding.modules.building.domain.model.Building;
import com.koreatech.indoor_pathfinding.modules.building.domain.repository.BuildingRepository;
import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;
import com.koreatech.indoor_pathfinding.modules.passage.domain.repository.VerticalPassageRepository;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.repository.PathEdgeRepository;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.repository.PathNodeRepository;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ScanChunk;
import com.koreatech.indoor_pathfinding.shared.exception.BusinessException;
import com.koreatech.indoor_pathfinding.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class BuildingDeleter {

    private final BuildingRepository buildingRepository;
    private final PathEdgeRepository pathEdgeRepository;
    private final PathNodeRepository pathNodeRepository;
    private final VerticalPassageRepository verticalPassageRepository;

    public void delete(UUID id) {
        Building building = buildingRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.BUILDING_NOT_FOUND));

        log.info("Deleting building '{}' ({})", building.getName(), id);

        pathEdgeRepository.deleteByBuildingId(id);
        pathNodeRepository.deleteByBuildingId(id);
        verticalPassageRepository.deleteByBuildingId(id);

        deleteFiles(building);

        buildingRepository.delete(building);

        log.info("Building '{}' and all related data deleted", building.getName());
    }

    public void deleteMultiple(List<UUID> ids) {
        for (UUID id : ids) {
            try {
                delete(id);
            } catch (Exception e) {
                log.error("Failed to delete building {}: {}", id, e.getMessage());
            }
        }
    }

    private void deleteFiles(Building building) {
        for (Floor floor : building.getFloors()) {
            for (ScanChunk chunk : floor.getScanChunks()) {
                deleteFile(chunk.getFilePath(), "chunk db");
            }
            if (floor.getMergedScan() != null && floor.getMergedScan().getFilePath() != null) {
                deleteFile(floor.getMergedScan().getFilePath(), "merged db");
            }
        }
        log.debug("File cleanup completed for building {}", building.getId());
    }

    private void deleteFile(String filePath, String label) {
        if (filePath == null) return;
        try {
            Path path = Path.of(filePath);
            if (Files.exists(path)) {
                Files.delete(path);
                log.debug("Deleted {} file: {}", label, filePath);
            }
        } catch (IOException e) {
            log.warn("Failed to delete {} file {}: {}", label, filePath, e.getMessage());
        }
    }
}
