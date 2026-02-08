package com.koreatech.indoor_pathfinding.modules.floor.application.query;

import com.koreatech.indoor_pathfinding.modules.building.domain.repository.BuildingRepository;
import com.koreatech.indoor_pathfinding.modules.floor.application.dto.response.FloorPathResponse;
import com.koreatech.indoor_pathfinding.modules.floor.application.dto.response.FloorResponse;
import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;
import com.koreatech.indoor_pathfinding.modules.floor.domain.model.FloorPath;
import com.koreatech.indoor_pathfinding.modules.floor.domain.repository.FloorPathRepository;
import com.koreatech.indoor_pathfinding.modules.floor.domain.repository.FloorRepository;
import com.koreatech.indoor_pathfinding.shared.exception.BusinessException;
import com.koreatech.indoor_pathfinding.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FloorReader {

    private final FloorRepository floorRepository;
    private final FloorPathRepository floorPathRepository;
    private final BuildingRepository buildingRepository;

    public List<FloorResponse> findByBuildingId(UUID buildingId) {
        if (!buildingRepository.existsById(buildingId)) {
            throw new BusinessException(ErrorCode.BUILDING_NOT_FOUND);
        }

        return floorRepository.findByBuildingIdOrderByLevelAsc(buildingId).stream()
            .map(FloorResponse::from)
            .toList();
    }

    public FloorResponse findById(UUID floorId) {
        Floor floor = floorRepository.findById(floorId)
            .orElseThrow(() -> new BusinessException(ErrorCode.FLOOR_NOT_FOUND));

        return FloorResponse.from(floor);
    }

    public Floor findEntityById(UUID floorId) {
        return floorRepository.findById(floorId)
            .orElseThrow(() -> new BusinessException(ErrorCode.FLOOR_NOT_FOUND));
    }

    public Optional<Floor> findByBuildingIdAndLevel(UUID buildingId, int level) {
        return floorRepository.findByBuildingIdAndLevel(buildingId, level);
    }

    public FloorPathResponse findPathByFloorId(UUID floorId) {
        FloorPath floorPath = floorPathRepository.findByFloorIdWithSegments(floorId)
            .orElseThrow(() -> new BusinessException(ErrorCode.PATH_NOT_FOUND));

        return FloorPathResponse.from(floorPath);
    }
}
