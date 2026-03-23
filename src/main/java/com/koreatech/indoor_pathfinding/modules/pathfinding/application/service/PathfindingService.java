package com.koreatech.indoor_pathfinding.modules.pathfinding.application.service;

import com.koreatech.indoor_pathfinding.modules.building.domain.repository.BuildingRepository;
import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;
import com.koreatech.indoor_pathfinding.modules.floor.domain.repository.FloorRepository;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.request.PathfindingRequest;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.response.PathfindingResponse;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.response.PathfindingResponse.*;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.query.PoiReader;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.*;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.repository.PathEdgeRepository;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.repository.PathNodeRepository;
import com.koreatech.indoor_pathfinding.shared.exception.BusinessException;
import com.koreatech.indoor_pathfinding.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PathfindingService {

    private static final double WALKING_SPEED_MPS = 1.4;

    private final PathNodeRepository pathNodeRepository;
    private final FloorRepository floorRepository;
    private final BuildingRepository buildingRepository;
    private final PathEdgeRepository pathEdgeRepository;
    private final PoiReader poiReader;
    private final AStarPathfinder aStarPathfinder;

    public PathfindingResponse findPath(UUID buildingId, PathfindingRequest request) {
        log.info("Finding path in building {} from floor {} ({}, {}) to '{}'",
            buildingId, request.startFloorLevel(), request.startX(), request.startY(),
            request.destinationName());

        if (!buildingRepository.existsById(buildingId)) {
            throw new BusinessException(ErrorCode.BUILDING_NOT_FOUND);
        }

        List<PathNode> nodes = pathNodeRepository.findByBuildingId(buildingId);
        if (nodes.isEmpty()) {
            throw new BusinessException(ErrorCode.GRAPH_NOT_BUILT,
                "No pathfinding graph available for this building");
        }

        Floor startFloor = floorRepository.findByBuildingIdAndLevel(
                buildingId, request.startFloorLevel())
            .orElseThrow(() -> new BusinessException(ErrorCode.FLOOR_NOT_FOUND,
                "Floor " + request.startFloorLevel() + " not found"));

        PathNode goalNode = poiReader.findNodeByPoiName(buildingId, request.destinationName());

        // 가장 가까운 엣지에 투영하여 시작 노드 결정
        PathNode startNode = findStartNodeByEdgeProjection(
            startFloor.getId(), buildingId,
            request.startX(), request.startY(),
            goalNode, request.getPreferenceOrDefault()
        );

        if (startNode.getId().equals(goalNode.getId())) {
            return buildAlreadyAtDestinationResponse(request, startNode);
        }

        PathPreference preference = request.getPreferenceOrDefault();
        List<PathNode> path = aStarPathfinder.findPath(startNode, goalNode, preference);

        if (path.isEmpty()) {
            throw new BusinessException(ErrorCode.NO_PATH_AVAILABLE);
        }

        return buildResponse(request, path);
    }

    /**
     * 가장 가까운 엣지에 사용자 위치를 투영하여 최적 시작 노드를 결정한다.
     * 가까운 엣지 순으로 시도하여, 목적지까지 경로가 존재하는 첫 번째 엣지를 사용한다.
     * (격리된 서브그래프 자동 스킵)
     */
    private PathNode findStartNodeByEdgeProjection(
            UUID floorId, UUID buildingId,
            double userX, double userY,
            PathNode goalNode, PathPreference preference) {

        List<PathEdge> floorEdges = pathEdgeRepository.findByFloorIdWithNodes(floorId)
            .stream()
            .filter(e -> e.getEdgeType() == EdgeType.HORIZONTAL)
            .toList();

        if (floorEdges.isEmpty()) {
            return pathNodeRepository.findNearestNodeOnFloor(floorId, userX, userY, 0.0)
                .orElseThrow(() -> new BusinessException(ErrorCode.NODE_NOT_FOUND));
        }

        // 모든 엣지를 투영 거리 순으로 정렬
        record EdgeProjection(PathEdge edge, double projDist, double distToFrom, double distToTo) {}

        List<EdgeProjection> projections = new ArrayList<>();

        for (PathEdge edge : floorEdges) {
            PathNode from = edge.getFromNode();
            PathNode to = edge.getToNode();

            double dx = to.getX() - from.getX();
            double dy = to.getY() - from.getY();
            double lenSq = dx * dx + dy * dy;
            if (lenSq == 0) continue;

            double t = Math.max(0, Math.min(1,
                ((userX - from.getX()) * dx + (userY - from.getY()) * dy) / lenSq));

            double projX = from.getX() + t * dx;
            double projY = from.getY() + t * dy;
            double projDist = Math.sqrt((projX - userX) * (projX - userX) + (projY - userY) * (projY - userY));
            double dFrom = Math.sqrt((projX - from.getX()) * (projX - from.getX()) +
                (projY - from.getY()) * (projY - from.getY()));
            double dTo = Math.sqrt((projX - to.getX()) * (projX - to.getX()) +
                (projY - to.getY()) * (projY - to.getY()));

            projections.add(new EdgeProjection(edge, projDist, dFrom, dTo));
        }

        projections.sort(Comparator.comparingDouble(ep -> ep.projDist));

        // 가까운 엣지부터 시도 - 경로가 존재하는 첫 번째 엣지 사용
        for (EdgeProjection proj : projections) {
            PathNode fromNode = proj.edge.getFromNode();
            PathNode toNode = proj.edge.getToNode();

            List<PathNode> pathViaFrom = aStarPathfinder.findPath(fromNode, goalNode, preference);
            List<PathNode> pathViaTo = aStarPathfinder.findPath(toNode, goalNode, preference);

            boolean fromReachable = !pathViaFrom.isEmpty();
            boolean toReachable = !pathViaTo.isEmpty();

            if (!fromReachable && !toReachable) {
                log.debug("Edge {}-{} skipped (disconnected from goal)",
                    fromNode.getId().toString().substring(0, 8),
                    toNode.getId().toString().substring(0, 8));
                continue;
            }

            double costFrom = fromReachable ? proj.distToFrom + totalPathDistance(pathViaFrom) : Double.MAX_VALUE;
            double costTo = toReachable ? proj.distToTo + totalPathDistance(pathViaTo) : Double.MAX_VALUE;

            PathNode chosen = costFrom <= costTo ? fromNode : toNode;
            log.info("Edge projection: edge {}-{} (projDist={}m), chose {} (costFrom={}, costTo={})",
                fromNode.getId().toString().substring(0, 8),
                toNode.getId().toString().substring(0, 8),
                String.format("%.1f", proj.projDist),
                chosen.getId().toString().substring(0, 8),
                String.format("%.1f", costFrom),
                String.format("%.1f", costTo));

            return chosen;
        }

        // 모든 엣지가 연결 안 됨 - 최후 폴백으로 nearest node
        return pathNodeRepository.findNearestNodeOnFloor(floorId, userX, userY, 0.0)
            .orElseThrow(() -> new BusinessException(ErrorCode.NO_PATH_AVAILABLE,
                "No connected path found from current position to destination"));
    }

    private double totalPathDistance(List<PathNode> path) {
        double total = 0;
        for (int i = 0; i < path.size() - 1; i++) {
            total += path.get(i).distanceTo(path.get(i + 1));
        }
        return total;
    }

    private PathfindingResponse buildAlreadyAtDestinationResponse(
            PathfindingRequest request, PathNode node) {
        double snapDistance = node.distanceTo(
            request.startX(), request.startY(), request.getStartZOrDefault());

        List<PathStep> steps = new ArrayList<>();

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

        double snapDistance = firstNode.distanceTo(
            request.startX(), request.startY(), request.getStartZOrDefault());

        if (snapDistance > 0.1) {
            totalDistance += snapDistance;
            steps.add(new PathStep(
                stepNumber++,
                firstNode.getFloor().getLevel(),
                new Position(request.startX(), request.startY(), request.getStartZOrDefault()),
                String.format("경로까지 %.1fm 이동", snapDistance)
            ));
        }

        PathNode previousNode = null;
        for (PathNode node : path) {
            if (previousNode != null) {
                totalDistance += previousNode.distanceTo(node);

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
        List<PathEdge> edges = pathEdgeRepository.findByNodePair(from.getId(), to.getId());
        for (PathEdge edge : edges) {
            if (edge.getEdgeType() == EdgeType.VERTICAL_ELEVATOR) {
                return "ELEVATOR";
            }
            if (edge.getEdgeType() == EdgeType.VERTICAL_STAIRCASE) {
                return "STAIRCASE";
            }
        }

        if (from.getVerticalPassage() != null) {
            return from.getVerticalPassage().getType().name();
        }
        if (to.getVerticalPassage() != null) {
            return to.getVerticalPassage().getType().name();
        }

        return "STAIRCASE";
    }

    private String generateInstruction(PathNode previous, PathNode current, List<PathNode> path) {
        if (previous == null) {
            return "출발";
        }

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

        if (current.getPoiName() != null) {
            return "목적지 '" + current.getPoiName() + "' 도착";
        }

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
