package com.koreatech.indoor_pathfinding.modules.floor.application.command;

import com.koreatech.indoor_pathfinding.modules.building.domain.model.Building;
import com.koreatech.indoor_pathfinding.modules.building.domain.repository.BuildingRepository;
import com.koreatech.indoor_pathfinding.modules.floor.application.dto.request.FloorCreateRequest;
import com.koreatech.indoor_pathfinding.modules.floor.application.dto.response.FloorResponse;
import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;
import com.koreatech.indoor_pathfinding.modules.floor.domain.repository.FloorRepository;
import com.koreatech.indoor_pathfinding.shared.exception.BusinessException;
import com.koreatech.indoor_pathfinding.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class FloorCreator {

    private final FloorRepository floorRepository;
    private final BuildingRepository buildingRepository;

    public FloorResponse create(UUID buildingId, FloorCreateRequest request) {
        Building building = buildingRepository.findById(buildingId)
            .orElseThrow(() -> new BusinessException(ErrorCode.BUILDING_NOT_FOUND));

        if (floorRepository.existsByBuildingIdAndLevel(buildingId, request.level())) {
            throw new BusinessException(ErrorCode.FLOOR_ALREADY_EXISTS,
                "Floor with level " + request.level() + " already exists");
        }

        Floor floor = Floor.builder()
            .name(request.name())
            .level(request.level())
            .height(request.height())
            .build();

        building.addFloor(floor);
        Floor saved = floorRepository.save(floor);

        return FloorResponse.from(saved);
    }
}
