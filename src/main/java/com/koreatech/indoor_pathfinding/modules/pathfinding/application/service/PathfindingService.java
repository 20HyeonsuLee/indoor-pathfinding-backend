package com.koreatech.indoor_pathfinding.modules.pathfinding.application.service;

import com.koreatech.indoor_pathfinding.modules.building.domain.repository.BuildingRepository;
import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;
import com.koreatech.indoor_pathfinding.modules.floor.domain.repository.FloorRepository;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.request.PathfindingRequest;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.response.PathfindingResponse;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.response.PathfindingResponse.*;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.query.PoiReader;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.NodeType;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.PathNode;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.PathPreference;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.repository.PathNodeRepository;
import com.koreatech.indoor_pathfinding.shared.exception.BusinessException;
import com.koreatech.indoor_pathfinding.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PathfindingService {

    private static final double WALKING_SPEED_MPS = 1.4; // 1.4 m/s average walking speed

    private final PathNodeRepository pathNodeRepository;
    private final FloorRepository floorRepository;
    private final BuildingRepository buildingRepository;
    private final PoiReader poiReader;
    private final AStarPathfinder aStarPathfinder;

    public PathfindingResponse findPath(UUID buildingId, PathfindingRequest request) {
        log.info("Finding path in building {} from floor {} ({}, {}) to '{}'",
            buildingId, request.startFloorLevel(), request.startX(), request.startY(),
            request.destinationName());

        // Validate building exists
        if (!buildingRepository.existsById(buildingId)) {
            throw new BusinessException(ErrorCode.BUILDING_NOT_FOUND);
        }

        // Check if graph exists
        List<PathNode> nodes = pathNodeRepository.findByBuildingId(buildingId);
        if (nodes.isEmpty()) {
            throw new BusinessException(ErrorCode.GRAPH_NOT_BUILT,
                "No pathfinding graph available for this building");
        }

        // Find start floor
        Floor startFloor = floorRepository.findByBuildingIdAndLevel(
                buildingId, request.startFloorLevel())
            .orElseThrow(() -> new BusinessException(ErrorCode.FLOOR_NOT_FOUND,
                "Floor " + request.startFloorLevel() + " not found"));

        // Find nearest node to start coordinates
        PathNode startNode = pathNodeRepository.findNearestNodeOnFloor(
                startFloor.getId(),
                request.startX(),
                request.startY(),
                request.getStartZOrDefault())
            .orElseThrow(() -> new BusinessException(ErrorCode.NODE_NOT_FOUND,
                "No node found near the start coordinates"));

        // Find destination node by POI name
        PathNode goalNode = poiReader.findNodeByPoiName(buildingId, request.destinationName());

        // Handle: start == goal
        if (startNode.getId().equals(goalNode.getId())) {
            return buildAlreadyAtDestinationResponse(request, startNode);
        }

        // Run A* algorithm
        PathPreference preference = request.getPreferenceOrDefault();
        List<PathNode> path = aStarPathfinder.findPath(startNode, goalNode, preference);

        if (path.isEmpty()) {
            throw new BusinessException(ErrorCode.NO_PATH_AVAILABLE);
        }

        return buildResponse(request, path);
    }

    private PathfindingResponse buildAlreadyAtDestinationResponse(
            PathfindingRequest request, PathNode node) {
        double snapDistance = node.distanceTo(
            request.startX(), request.startY(), request.getStartZOrDefault());

        List<PathStep> steps = new ArrayList<>();

        // 사용자 실제 위치 스텝
        if (snapDistance > 0.1) {
            steps.add(new PathStep(
                1,
                node.getFloor().getLevel(),
                new Position(request.startX(), request.startY(), request.getStartZOrDefault()),
                String.format("경로까지 %.1fm 이동", snapDistance)
            ));
        }

        steps.add(new PathStep(
            steps.size() + 1,
            node.getFloor().getLevel(),
            new Position(node.getX(), node.getY(), node.getZ()),
            "목적지 '" + node.getPoiName() + "' 도착"
        ));

        int estimatedTime = (int) Math.ceil(snapDistance / WALKING_SPEED_MPS);
        return new PathfindingResponse(snapDistance, estimatedTime, steps, List.of());
    }

    private PathfindingResponse buildResponse(PathfindingRequest request, List<PathNode> path) {
        List<PathStep> steps = new ArrayList<>();
        List<FloorTransition> transitions = new ArrayList<>();
        double totalDistance = 0;
        int stepNumber = 1;

        PathNode firstNode = path.get(0);

        // 사용자 실제 위치 → 첫 노드 간 보정 거리 추가
        double snapDistance = firstNode.distanceTo(
            request.startX(), request.startY(), request.getStartZOrDefault());

        if (snapDistance > 0.1) { // 10cm 이상 차이날 때만 보정 스텝 추가
            totalDistance += snapDistance;
            steps.add(new PathStep(
                stepNumber++,
                firstNode.getFloor().getLevel(),
                new Position(request.startX(), request.startY(), request.getStartZOrDefault()),
                String.format("경로까지 %.1fm 이동", snapDistance)
            ));
        }

        // 기존 경로 스텝 생성
        PathNode previousNode = null;
        for (PathNode node : path) {
            if (previousNode != null) {
                totalDistance += previousNode.distanceTo(node);

                // Check for floor transition
                if (!previousNode.getFloor().getId().equals(node.getFloor().getId())) {
                    String passageType = determinePassageType(previousNode, node);
                    transitions.add(new FloorTransition(
                        previousNode.getFloor().getLevel(),
                        node.getFloor().getLevel(),
                        passageType
                    ));
                }
            }

            String instruction = generateInstruction(previousNode, node, path);

            steps.add(new PathStep(
                stepNumber++,
                node.getFloor().getLevel(),
                new Position(node.getX(), node.getY(), node.getZ()),
                instruction
            ));

            previousNode = node;
        }

        int estimatedTime = (int) Math.ceil(totalDistance / WALKING_SPEED_MPS);
        return new PathfindingResponse(totalDistance, estimatedTime, steps, transitions);
    }

    private String determinePassageType(PathNode from, PathNode to) {
        if (from.getType() == NodeType.PASSAGE_ENTRY || from.getType() == NodeType.PASSAGE_EXIT) {
            if (from.getVerticalPassage() != null) {
                return from.getVerticalPassage().getType().name();
            }
        }
        if (to.getType() == NodeType.PASSAGE_ENTRY || to.getType() == NodeType.PASSAGE_EXIT) {
            if (to.getVerticalPassage() != null) {
                return to.getVerticalPassage().getType().name();
            }
        }
        return "UNKNOWN";
    }

    private String generateInstruction(PathNode previous, PathNode current, List<PathNode> path) {
        if (previous == null) {
            return "출발";
        }

        // Check for floor change
        if (!previous.getFloor().getId().equals(current.getFloor().getId())) {
            String passageType = determinePassageType(previous, current);
            int fromLevel = previous.getFloor().getLevel();
            int toLevel = current.getFloor().getLevel();

            if ("ELEVATOR".equals(passageType)) {
                return String.format("엘리베이터를 타고 %d층에서 %d층으로 이동", fromLevel, toLevel);
            } else {
                return String.format("계단을 이용하여 %d층에서 %d층으로 이동", fromLevel, toLevel);
            }
        }

        // Check if destination
        if (current.getPoiName() != null) {
            return "목적지 '" + current.getPoiName() + "' 도착";
        }

        // Calculate distance and direction
        double distance = previous.distanceTo(current);
        String direction = calculateDirection(previous, current);

        return String.format("%s 방향으로 %.1fm 이동", direction, distance);
    }

    private String calculateDirection(PathNode from, PathNode to) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();

        double angle = Math.toDegrees(Math.atan2(dy, dx));
        if (angle < 0) angle += 360;

        if (angle >= 337.5 || angle < 22.5) return "동쪽";
        if (angle >= 22.5 && angle < 67.5) return "북동쪽";
        if (angle >= 67.5 && angle < 112.5) return "북쪽";
        if (angle >= 112.5 && angle < 157.5) return "북서쪽";
        if (angle >= 157.5 && angle < 202.5) return "서쪽";
        if (angle >= 202.5 && angle < 247.5) return "남서쪽";
        if (angle >= 247.5 && angle < 292.5) return "남쪽";
        return "남동쪽";
    }
}
