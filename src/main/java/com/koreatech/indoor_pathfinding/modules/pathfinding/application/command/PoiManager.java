package com.koreatech.indoor_pathfinding.modules.pathfinding.application.command;

import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;
import com.koreatech.indoor_pathfinding.modules.floor.domain.repository.FloorRepository;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.request.PoiCreateRequest;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.request.PoiRegisterRequest;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.response.PoiResponse;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.PathNode;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.repository.PathNodeRepository;
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
public class PoiManager {

    private final PathNodeRepository pathNodeRepository;
    private final FloorRepository floorRepository;

    public PoiResponse createPoi(UUID buildingId, PoiCreateRequest request) {
        // 0. 같은 건물에 같은 이름의 POI가 이미 존재하는지 확인
        validatePoiNameUnique(buildingId, request.name());

        // 1. 해당 층 찾기
        Floor floor = floorRepository.findByBuildingIdAndLevel(buildingId, request.floorLevel())
            .orElseThrow(() -> new BusinessException(ErrorCode.FLOOR_NOT_FOUND,
                "Floor " + request.floorLevel() + " not found"));

        // 2. 좌표에서 가장 가까운 기존 노드 찾기 (허공 노드 생성 X)
        PathNode nearestNode = pathNodeRepository.findNearestNodeOnFloor(
                floor.getId(), request.x(), request.y(), request.getZOrDefault())
            .orElseThrow(() -> new BusinessException(ErrorCode.NODE_NOT_FOUND,
                "No nodes available on floor " + request.floorLevel()));

        // 3. 해당 노드에 POI 등록
        nearestNode.updatePoi(request.name(), request.category());
        PathNode saved = pathNodeRepository.save(nearestNode);

        log.info("Created POI '{}' on nearest node {} (floor {}F, distance from click: {}m)",
            request.name(), saved.getId(), request.floorLevel(),
            String.format("%.2f", saved.distanceTo(request.x(), request.y(), request.getZOrDefault())));

        return PoiResponse.from(saved);
    }

    public PoiResponse registerPoi(UUID nodeId, PoiRegisterRequest request) {
        PathNode node = pathNodeRepository.findById(nodeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NODE_NOT_FOUND));

        UUID buildingId = node.getFloor().getBuilding().getId();
        validatePoiNameUnique(buildingId, request.name());

        node.updatePoi(request.name(), request.category());
        PathNode saved = pathNodeRepository.save(node);

        log.info("Registered POI '{}' on node {}", request.name(), nodeId);
        return PoiResponse.from(saved);
    }

    public void deletePoi(UUID nodeId) {
        PathNode node = pathNodeRepository.findById(nodeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NODE_NOT_FOUND));

        if (node.getPoiName() == null) {
            throw new BusinessException(ErrorCode.POI_NOT_FOUND,
                "No POI registered on this node");
        }

        String poiName = node.getPoiName();
        node.clearPoi();
        pathNodeRepository.save(node);

        log.info("Deleted POI '{}' from node {}", poiName, nodeId);
    }

    private void validatePoiNameUnique(UUID buildingId, String poiName) {
        pathNodeRepository.findByBuildingIdAndPoiName(buildingId, poiName)
            .ifPresent(existing -> {
                throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE,
                    "POI with name '" + poiName + "' already exists in this building");
            });
    }
}
