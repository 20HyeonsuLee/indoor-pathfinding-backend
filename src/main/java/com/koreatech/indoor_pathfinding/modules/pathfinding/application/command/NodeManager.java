package com.koreatech.indoor_pathfinding.modules.pathfinding.application.command;

import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;
import com.koreatech.indoor_pathfinding.modules.floor.domain.repository.FloorRepository;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.request.NodeCreateRequest;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.request.NodeUpdateRequest;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.response.PathNodeResponse;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.PathNode;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.repository.PathEdgeRepository;
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
public class NodeManager {

    private final PathNodeRepository pathNodeRepository;
    private final PathEdgeRepository pathEdgeRepository;
    private final FloorRepository floorRepository;

    public PathNodeResponse createNode(UUID floorId, NodeCreateRequest request) {
        Floor floor = floorRepository.findById(floorId)
            .orElseThrow(() -> new BusinessException(ErrorCode.FLOOR_NOT_FOUND));

        PathNode node = PathNode.builder()
            .floor(floor)
            .x(request.x())
            .y(request.y())
            .z(request.zOrDefault())
            .type(request.type())
            .build();

        PathNode saved = pathNodeRepository.save(node);
        log.info("Created node {} on floor {} at ({}, {}, {})",
            saved.getId(), floorId, request.x(), request.y(), request.zOrDefault());

        return PathNodeResponse.from(saved);
    }

    public PathNodeResponse updateNode(UUID nodeId, NodeUpdateRequest request) {
        PathNode node = pathNodeRepository.findById(nodeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NODE_NOT_FOUND));

        if (request.x() != null && request.y() != null) {
            double z = request.z() != null ? request.z() : node.getZ();
            node.updateCoordinates(request.x(), request.y(), z);

            pathEdgeRepository.findAllByNodeId(nodeId)
                .forEach(edge -> {
                    edge.recalculateDistance();
                    pathEdgeRepository.save(edge);
                });
        }

        if (request.type() != null) {
            node.updateType(request.type());
        }

        PathNode saved = pathNodeRepository.save(node);
        log.info("Updated node {}", nodeId);
        return PathNodeResponse.from(saved);
    }

    public void deleteNode(UUID nodeId) {
        PathNode node = pathNodeRepository.findById(nodeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NODE_NOT_FOUND));

        pathEdgeRepository.findAllByNodeId(nodeId)
            .forEach(pathEdgeRepository::delete);

        pathNodeRepository.delete(node);
        log.info("Deleted node {} and its connected edges", nodeId);
    }

    public void deleteAllByFloorId(UUID floorId) {
        floorRepository.findById(floorId)
            .orElseThrow(() -> new BusinessException(ErrorCode.FLOOR_NOT_FOUND));

        pathEdgeRepository.deleteByFloorId(floorId);
        pathNodeRepository.deleteByFloorId(floorId);
        log.info("Cleared all nodes and edges on floor {}", floorId);
    }
}
