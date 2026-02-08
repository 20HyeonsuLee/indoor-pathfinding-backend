package com.koreatech.indoor_pathfinding.modules.building.application.query;

import com.koreatech.indoor_pathfinding.modules.building.application.dto.response.BuildingDetailResponse;
import com.koreatech.indoor_pathfinding.modules.building.application.dto.response.BuildingResponse;
import com.koreatech.indoor_pathfinding.modules.building.domain.model.Building;
import com.koreatech.indoor_pathfinding.modules.building.domain.model.BuildingStatus;
import com.koreatech.indoor_pathfinding.modules.building.domain.repository.BuildingRepository;
import com.koreatech.indoor_pathfinding.shared.exception.BusinessException;
import com.koreatech.indoor_pathfinding.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BuildingReader {

    private final BuildingRepository buildingRepository;

    public List<BuildingResponse> findAll() {
        return buildingRepository.findAll().stream()
            .map(BuildingResponse::from)
            .toList();
    }

    public List<BuildingResponse> findByStatus(BuildingStatus status) {
        return buildingRepository.findByStatus(status).stream()
            .map(BuildingResponse::from)
            .toList();
    }

    public BuildingDetailResponse findById(UUID id) {
        Building building = buildingRepository.findByIdWithFloorsAndPassages(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.BUILDING_NOT_FOUND));

        return BuildingDetailResponse.from(building);
    }

    public Building findEntityById(UUID id) {
        return buildingRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.BUILDING_NOT_FOUND));
    }

    public boolean existsById(UUID id) {
        return buildingRepository.existsById(id);
    }
}
