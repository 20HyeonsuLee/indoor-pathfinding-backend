package com.koreatech.indoor_pathfinding.modules.building.application.command;

import com.koreatech.indoor_pathfinding.modules.building.application.dto.request.BuildingCreateRequest;
import com.koreatech.indoor_pathfinding.modules.building.application.dto.request.BuildingUpdateRequest;
import com.koreatech.indoor_pathfinding.modules.building.application.dto.response.BuildingResponse;
import com.koreatech.indoor_pathfinding.modules.building.domain.model.Building;
import com.koreatech.indoor_pathfinding.modules.building.domain.model.BuildingStatus;
import com.koreatech.indoor_pathfinding.modules.building.domain.repository.BuildingRepository;
import com.koreatech.indoor_pathfinding.shared.exception.BusinessException;
import com.koreatech.indoor_pathfinding.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class BuildingUpdater {

    private final BuildingRepository buildingRepository;
    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    public BuildingResponse update(UUID id, BuildingUpdateRequest request) {
        Building building = buildingRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.BUILDING_NOT_FOUND));

        building.updateInfo(request.name(), request.description());
        building.updateLocation(createLocation(request));

        return BuildingResponse.from(building);
    }

    public BuildingResponse updateStatus(UUID id, BuildingStatus status) {
        Building building = buildingRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.BUILDING_NOT_FOUND));

        building.updateStatus(status);

        return BuildingResponse.from(building);
    }

    private Point createLocation(BuildingUpdateRequest request) {
        if (request.latitude() == null || request.longitude() == null) {
            return null;
        }
        return geometryFactory.createPoint(new Coordinate(request.longitude(), request.latitude()));
    }
}
