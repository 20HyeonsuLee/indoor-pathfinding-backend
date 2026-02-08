package com.koreatech.indoor_pathfinding.modules.pathfinding.application.service;

import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.PathEdge;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.PathNode;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.PathPreference;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.repository.PathEdgeRepository;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.repository.PathNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class AStarPathfinder {

    private final PathNodeRepository pathNodeRepository;
    private final PathEdgeRepository pathEdgeRepository;

    public List<PathNode> findPath(PathNode start, PathNode goal, PathPreference preference) {
        log.info("Finding path from {} to {} with preference {} using Java A*",
            start.getId(), goal.getId(), preference);

        UUID buildingId = start.getFloor().getBuilding().getId();

        // 1. 모든 노드와 엣지 로드
        Map<UUID, PathNode> nodeMap = new HashMap<>();
        pathNodeRepository.findByBuildingId(buildingId).forEach(n -> nodeMap.put(n.getId(), n));

        // 2. 엣지 기반 인접 리스트 구축
        Map<UUID, List<NeighborInfo>> adjacency = new HashMap<>();
        for (PathEdge edge : pathEdgeRepository.findByBuildingId(buildingId)) {
            double cost = edge.getWeightedDistance(preference);

            // from -> to
            adjacency.computeIfAbsent(edge.getFromNode().getId(), k -> new ArrayList<>())
                .add(new NeighborInfo(edge.getToNode().getId(), cost));

            // to -> from (양방향)
            if (Boolean.TRUE.equals(edge.getIsBidirectional())) {
                adjacency.computeIfAbsent(edge.getToNode().getId(), k -> new ArrayList<>())
                    .add(new NeighborInfo(edge.getFromNode().getId(), cost));
            }
        }

        // 3. A* 탐색
        PriorityQueue<AStarNode> openSet = new PriorityQueue<>(
            Comparator.comparingDouble(AStarNode::fScore)
        );
        Set<UUID> closedSet = new HashSet<>();
        Map<UUID, AStarNode> bestNodes = new HashMap<>();

        AStarNode startNode = new AStarNode(start, null, 0, heuristic(start, goal));
        openSet.add(startNode);
        bestNodes.put(start.getId(), startNode);

        while (!openSet.isEmpty()) {
            AStarNode current = openSet.poll();

            if (current.node.getId().equals(goal.getId())) {
                List<PathNode> path = reconstructPath(current);
                log.info("Found path with {} nodes, total distance estimated", path.size());
                return path;
            }

            if (closedSet.contains(current.node.getId())) {
                continue;
            }
            closedSet.add(current.node.getId());

            List<NeighborInfo> neighbors = adjacency.getOrDefault(current.node.getId(), List.of());
            for (NeighborInfo neighbor : neighbors) {
                if (closedSet.contains(neighbor.nodeId)) {
                    continue;
                }

                PathNode neighborNode = nodeMap.get(neighbor.nodeId);
                if (neighborNode == null) continue;

                double tentativeG = current.gScore + neighbor.cost;
                AStarNode bestNeighbor = bestNodes.get(neighbor.nodeId);

                if (bestNeighbor == null || tentativeG < bestNeighbor.gScore) {
                    AStarNode newNode = new AStarNode(neighborNode, current, tentativeG,
                        heuristic(neighborNode, goal));
                    bestNodes.put(neighbor.nodeId, newNode);
                    openSet.add(newNode);
                }
            }
        }

        log.warn("No path found from {} to {}", start.getId(), goal.getId());
        return Collections.emptyList();
    }

    private double heuristic(PathNode from, PathNode to) {
        return from.distanceTo(to);
    }

    private List<PathNode> reconstructPath(AStarNode goalNode) {
        List<PathNode> path = new ArrayList<>();
        AStarNode current = goalNode;
        while (current != null) {
            path.add(current.node);
            current = current.parent;
        }
        Collections.reverse(path);
        return path;
    }

    private record NeighborInfo(UUID nodeId, double cost) {}

    private record AStarNode(
        PathNode node,
        AStarNode parent,
        double gScore,
        double hScore
    ) {
        double fScore() {
            return gScore + hScore;
        }
    }
}
