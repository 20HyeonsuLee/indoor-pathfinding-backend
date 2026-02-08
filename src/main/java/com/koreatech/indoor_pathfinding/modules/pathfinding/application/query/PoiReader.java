package com.koreatech.indoor_pathfinding.modules.pathfinding.application.query;

import com.koreatech.indoor_pathfinding.modules.building.domain.repository.BuildingRepository;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.response.PoiResponse;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.PathNode;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.repository.PathNodeRepository;
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
public class PoiReader {

    private final PathNodeRepository pathNodeRepository;
    private final BuildingRepository buildingRepository;

    public List<PoiResponse> findAllByBuildingId(UUID buildingId) {
        if (!buildingRepository.existsById(buildingId)) {
            throw new BusinessException(ErrorCode.BUILDING_NOT_FOUND);
        }

        return pathNodeRepository.findPoisByBuildingId(buildingId)
            .stream()
            .map(PoiResponse::from)
            .toList();
    }

    public List<PoiResponse> searchByName(UUID buildingId, String query) {
        if (!buildingRepository.existsById(buildingId)) {
            throw new BusinessException(ErrorCode.BUILDING_NOT_FOUND);
        }

        if (query == null || query.isBlank()) {
            return findAllByBuildingId(buildingId);
        }

        return pathNodeRepository.searchPoisByBuildingIdAndName(buildingId, query)
            .stream()
            .map(PoiResponse::from)
            .toList();
    }

    public PathNode findNodeByPoiName(UUID buildingId, String poiName) {
        return pathNodeRepository.findByBuildingIdAndPoiName(buildingId, poiName)
            .orElseThrow(() -> new BusinessException(ErrorCode.POI_NOT_FOUND,
                "POI not found: " + poiName));
    }
}
