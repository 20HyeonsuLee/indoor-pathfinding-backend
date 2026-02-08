package com.koreatech.indoor_pathfinding.modules.building.application.command;

import com.koreatech.indoor_pathfinding.modules.building.application.dto.request.BuildingCreateRequest;
import com.koreatech.indoor_pathfinding.modules.building.application.dto.response.BuildingResponse;
import com.koreatech.indoor_pathfinding.modules.building.domain.model.Building;
import com.koreatech.indoor_pathfinding.modules.building.domain.model.BuildingStatus;
import com.koreatech.indoor_pathfinding.modules.building.domain.repository.BuildingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class BuildingCreator {

    private final BuildingRepository buildingRepository;
    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    public BuildingResponse create(BuildingCreateRequest request) {
        Building building = Building.builder()
            .name(request.name())
            .description(request.description())
            .location(createLocation(request))
            .status(BuildingStatus.DRAFT)
            .build();

        return BuildingResponse.from(buildingRepository.save(building));
    }

    private Point createLocation(BuildingCreateRequest request) {
        if (request.latitude() == null || request.longitude() == null) {
            return null;
        }
        return geometryFactory.createPoint(new Coordinate(request.longitude(), request.latitude()));
    }
}
