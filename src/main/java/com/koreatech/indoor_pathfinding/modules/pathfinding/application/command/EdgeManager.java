package com.koreatech.indoor_pathfinding.modules.pathfinding.application.command;

import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.request.EdgeCreateRequest;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.response.PathEdgeResponse;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.EdgeType;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.PathEdge;
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
public class EdgeManager {

    private final PathEdgeRepository pathEdgeRepository;
    private final PathNodeRepository pathNodeRepository;

    public PathEdgeResponse createEdge(EdgeCreateRequest request) {
        PathNode fromNode = pathNodeRepository.findById(request.fromNodeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NODE_NOT_FOUND,
                "From node not found: " + request.fromNodeId()));

        PathNode toNode = pathNodeRepository.findById(request.toNodeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NODE_NOT_FOUND,
                "To node not found: " + request.toNodeId()));

        EdgeType edgeType = request.edgeTypeOrDefault();

        // 같은 타입의 엣지만 중복 체크 (수직/수평 엣지는 같은 노드 쌍에 공존 가능)
        boolean duplicate = pathEdgeRepository.findByNodePair(request.fromNodeId(), request.toNodeId())
            .stream()
            .anyMatch(e -> e.getEdgeType() == edgeType);

        if (duplicate) {
            throw new BusinessException(ErrorCode.DUPLICATE_EDGE);
        }

        double distance = fromNode.distanceTo(toNode);

        PathEdge edge = PathEdge.builder()
            .fromNode(fromNode)
            .toNode(toNode)
            .distance(distance)
            .edgeType(edgeType)
            .isBidirectional(request.bidirectionalOrDefault())
            .build();

        PathEdge saved = pathEdgeRepository.save(edge);
        log.info("Created edge {} -> {} (type={}, distance={}m)",
            request.fromNodeId(), request.toNodeId(), edgeType, String.format("%.2f", distance));

        return PathEdgeResponse.from(saved);
    }

    public void deleteEdge(UUID edgeId) {
        PathEdge edge = pathEdgeRepository.findById(edgeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.EDGE_NOT_FOUND));

        pathEdgeRepository.delete(edge);
        log.info("Deleted edge {}", edgeId);
    }
}
