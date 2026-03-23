package com.koreatech.indoor_pathfinding.modules.pathfinding.interfaces.controller;

import com.koreatech.indoor_pathfinding.modules.pathfinding.application.command.EdgeManager;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.command.NodeManager;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.request.EdgeCreateRequest;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.request.NodeCreateRequest;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.request.NodeUpdateRequest;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.response.FloorGraphResponse;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.response.PathEdgeResponse;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.response.PathNodeResponse;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.query.GraphReader;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class GraphEditorController {

    private final NodeManager nodeManager;
    private final EdgeManager edgeManager;
    private final GraphReader graphReader;

    @GetMapping("/floors/{floorId}/graph")
    public ResponseEntity<FloorGraphResponse> getFloorGraph(@PathVariable UUID floorId) {
        return ResponseEntity.ok(graphReader.findGraphByFloorId(floorId));
    }

    @PostMapping("/floors/{floorId}/nodes")
    public ResponseEntity<PathNodeResponse> createNode(
            @PathVariable UUID floorId,
            @Valid @RequestBody NodeCreateRequest request) {
        return ResponseEntity.ok(nodeManager.createNode(floorId, request));
    }

    @PutMapping("/nodes/{nodeId}")
    public ResponseEntity<PathNodeResponse> updateNode(
            @PathVariable UUID nodeId,
            @Valid @RequestBody NodeUpdateRequest request) {
        return ResponseEntity.ok(nodeManager.updateNode(nodeId, request));
    }

    @DeleteMapping("/nodes/{nodeId}")
    public ResponseEntity<Void> deleteNode(@PathVariable UUID nodeId) {
        nodeManager.deleteNode(nodeId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/floors/{floorId}/edges")
    public ResponseEntity<PathEdgeResponse> createEdge(
            @PathVariable UUID floorId,
            @Valid @RequestBody EdgeCreateRequest request) {
        return ResponseEntity.ok(edgeManager.createEdge(request));
    }

    @DeleteMapping("/edges/{edgeId}")
    public ResponseEntity<Void> deleteEdge(@PathVariable UUID edgeId) {
        edgeManager.deleteEdge(edgeId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/floors/{floorId}/graph")
    public ResponseEntity<Void> clearFloorGraph(@PathVariable UUID floorId) {
        nodeManager.deleteAllByFloorId(floorId);
        return ResponseEntity.noContent().build();
    }
}
