package com.koreatech.indoor_pathfinding.modules.pathfinding.application.command;

import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;
import com.koreatech.indoor_pathfinding.modules.floor.domain.model.FloorPath;
import com.koreatech.indoor_pathfinding.modules.floor.domain.repository.FloorRepository;
import com.koreatech.indoor_pathfinding.modules.passage.domain.model.PassageType;
import com.koreatech.indoor_pathfinding.modules.passage.domain.model.VerticalPassage;
import com.koreatech.indoor_pathfinding.modules.passage.domain.repository.VerticalPassageRepository;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.*;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.repository.PathEdgeRepository;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.repository.PathNodeRepository;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.domain.model.PathSegment;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.domain.model.Point3D;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class GraphBuilder {

    private static final double COORDINATE_TOLERANCE = 0.001;

    private final FloorRepository floorRepository;
    private final VerticalPassageRepository verticalPassageRepository;
    private final PathNodeRepository pathNodeRepository;
    private final PathEdgeRepository pathEdgeRepository;

    public void buildGraphForBuilding(UUID buildingId) {
        log.info("Building graph for building: {}", buildingId);

        // Clear existing graph data for this building
        pathEdgeRepository.deleteByBuildingId(buildingId);
        pathNodeRepository.deleteByBuildingId(buildingId);

        // Get all floors for the building
        List<Floor> floors = floorRepository.findByBuildingIdOrderByLevelAsc(buildingId);

        // Node cache to deduplicate nodes by coordinates
        Map<String, PathNode> nodeCache = new HashMap<>();

        // Build nodes and edges for each floor
        for (Floor floor : floors) {
            buildFloorGraph(floor, nodeCache);
        }

        // Build vertical passage connections
        List<VerticalPassage> passages = verticalPassageRepository.findByBuildingId(buildingId);
        for (VerticalPassage passage : passages) {
            buildPassageConnections(passage, nodeCache);
        }

        // Identify and mark junction nodes
        markJunctionNodes(nodeCache.values());

        log.info("Graph built for building: {}. Nodes: {}, checking edges...",
            buildingId, nodeCache.size());
    }

    private void buildFloorGraph(Floor floor, Map<String, PathNode> nodeCache) {
        FloorPath floorPath = floor.getFloorPath();
        if (floorPath == null || floorPath.getSegments().isEmpty()) {
            log.debug("No path data for floor: {}", floor.getId());
            return;
        }

        List<PathSegment> segments = floorPath.getSegments();
        PathNode previousEndNode = null;

        for (PathSegment segment : segments) {
            Point3D startPoint = segment.getStartPoint();
            Point3D endPoint = segment.getEndPoint();

            // Get or create nodes for start and end points
            PathNode startNode = getOrCreateNode(floor, startPoint, nodeCache);
            PathNode endNode = getOrCreateNode(floor, endPoint, nodeCache);

            // Create edge between start and end
            createEdge(startNode, endNode, EdgeType.HORIZONTAL, segment);

            // Connect to previous segment's end node if coordinates match
            if (previousEndNode != null && !previousEndNode.getId().equals(startNode.getId())) {
                // If they're different nodes but segment order is sequential, connect them
                createEdge(previousEndNode, startNode, EdgeType.HORIZONTAL, null);
            }

            previousEndNode = endNode;
        }
    }

    private void buildPassageConnections(VerticalPassage passage, Map<String, PathNode> nodeCache) {
        Floor fromFloor = passage.getFromFloor();
        Floor toFloor = passage.getToFloor();

        Point3D entryPoint = passage.getEntryPoint();
        Point3D exitPoint = passage.getExitPoint();

        if (entryPoint == null || exitPoint == null) {
            log.warn("Passage {} has no entry/exit points", passage.getId());
            return;
        }

        // Create or get entry node on fromFloor
        PathNode entryNode = getOrCreatePassageNode(
            fromFloor, entryPoint, passage, true, nodeCache);

        // Create or get exit node on toFloor
        PathNode exitNode = getOrCreatePassageNode(
            toFloor, exitPoint, passage, false, nodeCache);

        // Determine edge type based on passage type
        EdgeType edgeType = passage.getType() == PassageType.ELEVATOR
            ? EdgeType.VERTICAL_ELEVATOR
            : EdgeType.VERTICAL_STAIRCASE;

        // Create vertical edge between entry and exit
        createEdge(entryNode, exitNode, edgeType, null);

        // Connect entry node to nearest floor node
        connectToNearestFloorNode(entryNode, fromFloor, nodeCache);
        connectToNearestFloorNode(exitNode, toFloor, nodeCache);
    }

    private PathNode getOrCreateNode(Floor floor, Point3D point, Map<String, PathNode> nodeCache) {
        String key = createNodeKey(floor.getId(), point);

        return nodeCache.computeIfAbsent(key, k -> {
            PathNode node = PathNode.builder()
                .floor(floor)
                .x(point.getX())
                .y(point.getY())
                .z(point.getZ())
                .type(NodeType.WAYPOINT)
                .isPassageEntry(false)
                .build();
            return pathNodeRepository.save(node);
        });
    }

    private PathNode getOrCreatePassageNode(Floor floor, Point3D point,
                                            VerticalPassage passage, boolean isEntry,
                                            Map<String, PathNode> nodeCache) {
        String key = createNodeKey(floor.getId(), point);

        PathNode existingNode = nodeCache.get(key);
        if (existingNode != null) {
            return existingNode;
        }

        PathNode node = PathNode.builder()
            .floor(floor)
            .x(point.getX())
            .y(point.getY())
            .z(point.getZ())
            .type(isEntry ? NodeType.PASSAGE_ENTRY : NodeType.PASSAGE_EXIT)
            .verticalPassage(passage)
            .isPassageEntry(isEntry)
            .poiCategory(passage.getType() == PassageType.ELEVATOR
                ? PoiCategory.ELEVATOR : PoiCategory.STAIRCASE)
            .build();

        node = pathNodeRepository.save(node);
        nodeCache.put(key, node);
        return node;
    }

    private void connectToNearestFloorNode(PathNode passageNode, Floor floor,
                                           Map<String, PathNode> nodeCache) {
        // Find nearest node that's not a passage node
        PathNode nearestNode = null;
        double minDistance = Double.MAX_VALUE;

        for (PathNode node : nodeCache.values()) {
            if (!node.getFloor().getId().equals(floor.getId())) continue;
            if (node.getId().equals(passageNode.getId())) continue;
            if (node.getType() == NodeType.PASSAGE_ENTRY ||
                node.getType() == NodeType.PASSAGE_EXIT) continue;

            double distance = passageNode.distanceTo(node);
            if (distance < minDistance) {
                minDistance = distance;
                nearestNode = node;
            }
        }

        if (nearestNode != null) {
            createEdge(passageNode, nearestNode, EdgeType.HORIZONTAL, null);
        }
    }

    private void createEdge(PathNode fromNode, PathNode toNode, EdgeType edgeType,
                           PathSegment segment) {
        if (fromNode.getId().equals(toNode.getId())) {
            return; // Don't create self-loops
        }

        double distance = fromNode.distanceTo(toNode);

        PathEdge edge = PathEdge.builder()
            .fromNode(fromNode)
            .toNode(toNode)
            .distance(distance)
            .edgeType(edgeType)
            .pathSegment(segment)
            .isBidirectional(true)
            .build();

        pathEdgeRepository.save(edge);
    }

    private void markJunctionNodes(Collection<PathNode> nodes) {
        // Count connections for each node
        Map<UUID, Integer> connectionCount = new HashMap<>();

        List<PathEdge> allEdges = pathEdgeRepository.findAll();
        for (PathEdge edge : allEdges) {
            connectionCount.merge(edge.getFromNode().getId(), 1, Integer::sum);
            if (edge.getIsBidirectional()) {
                connectionCount.merge(edge.getToNode().getId(), 1, Integer::sum);
            }
        }

        // Mark nodes with more than 2 connections as junctions
        for (PathNode node : nodes) {
            int count = connectionCount.getOrDefault(node.getId(), 0);
            if (count > 2 && node.getType() == NodeType.WAYPOINT) {
                // Note: We can't directly modify the type here in this pattern,
                // but junction status can be determined dynamically
                log.debug("Junction node found: {} with {} connections",
                    node.getId(), count);
            }
        }
    }

    private String createNodeKey(UUID floorId, Point3D point) {
        // Round coordinates to avoid floating point precision issues
        long x = Math.round(point.getX() / COORDINATE_TOLERANCE);
        long y = Math.round(point.getY() / COORDINATE_TOLERANCE);
        long z = Math.round(point.getZ() / COORDINATE_TOLERANCE);
        return String.format("%s:%d:%d:%d", floorId, x, y, z);
    }
}
