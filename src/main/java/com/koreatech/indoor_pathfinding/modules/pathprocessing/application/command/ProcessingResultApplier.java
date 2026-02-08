package com.koreatech.indoor_pathfinding.modules.pathprocessing.application.command;

import com.koreatech.indoor_pathfinding.modules.building.domain.model.Building;
import com.koreatech.indoor_pathfinding.modules.building.domain.model.BuildingStatus;
import com.koreatech.indoor_pathfinding.modules.building.domain.repository.BuildingRepository;
import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;
import com.koreatech.indoor_pathfinding.modules.floor.domain.model.FloorPath;
import com.koreatech.indoor_pathfinding.modules.floor.domain.repository.FloorPathRepository;
import com.koreatech.indoor_pathfinding.modules.floor.domain.repository.FloorRepository;
import com.koreatech.indoor_pathfinding.modules.passage.domain.model.PassageType;
import com.koreatech.indoor_pathfinding.modules.passage.domain.model.VerticalPassage;
import com.koreatech.indoor_pathfinding.modules.passage.domain.repository.VerticalPassageRepository;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.command.GraphBuilder;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.application.dto.response.ProcessingStatusResponse;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.domain.model.PathSegment;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.domain.model.Point3D;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.infrastructure.external.PathProcessingClient;
import com.koreatech.indoor_pathfinding.modules.scan.application.command.ScanResultRecorder;
import com.koreatech.indoor_pathfinding.modules.scan.application.query.ScanSessionReader;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ScanSession;
import com.koreatech.indoor_pathfinding.shared.exception.BusinessException;
import com.koreatech.indoor_pathfinding.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ProcessingResultApplier {

    private final PathProcessingClient pathProcessingClient;
    private final ScanSessionReader scanSessionReader;
    private final ScanResultRecorder scanResultRecorder;
    private final BuildingRepository buildingRepository;
    private final FloorRepository floorRepository;
    private final FloorPathRepository floorPathRepository;
    private final VerticalPassageRepository verticalPassageRepository;
    private final GraphBuilder graphBuilder;

    public void apply(UUID sessionId) {
        log.info("Applying processing result for session: {}", sessionId);

        String jobId = ProcessingStarter.getJobIdForSession(sessionId);
        if (jobId == null) {
            throw new BusinessException(ErrorCode.SCAN_SESSION_NOT_FOUND,
                "No processing job found for session");
        }

        // Get job status
        ProcessingStatusResponse status = pathProcessingClient.getJobStatus(jobId);

        if (!"COMPLETED".equals(status.status())) {
            throw new BusinessException(ErrorCode.SCAN_PROCESSING_FAILED,
                "Processing is not completed. Status: " + status.status());
        }

        // Get result
        Map<String, Object> result = pathProcessingClient.getJobResult(jobId);

        ScanSession session = scanSessionReader.findEntityById(sessionId);
        Building building = session.getBuilding();

        // Apply floor paths
        List<Map<String, Object>> floorPaths = (List<Map<String, Object>>) result.get("floor_paths");
        for (Map<String, Object> floorPathData : floorPaths) {
            applyFloorPath(building, floorPathData);
        }

        // Apply vertical passages
        List<Map<String, Object>> passages = (List<Map<String, Object>>) result.get("vertical_passages");
        for (Map<String, Object> passageData : passages) {
            applyVerticalPassage(building, passageData);
        }

        // Update session with result
        int totalNodes = ((Number) result.get("total_nodes")).intValue();
        double totalDistance = ((Number) result.get("total_distance")).doubleValue();
        String previewPath = (String) result.get("preview_image_path");
        String processedPath = (String) result.get("processed_preview_path");

        scanResultRecorder.recordResult(sessionId, previewPath, processedPath,
            totalNodes, totalDistance);

        // Update building status
        building.updateStatus(BuildingStatus.ACTIVE);
        buildingRepository.save(building);

        // Build pathfinding graph
        graphBuilder.buildGraphForBuilding(building.getId());

        log.info("Applied processing result for session: {}", sessionId);
    }

    private void applyFloorPath(Building building, Map<String, Object> floorPathData) {
        int floorLevel = ((Number) floorPathData.get("floor_level")).intValue();
        String floorName = (String) floorPathData.get("floor_name");

        // Find or create floor
        Floor floor = floorRepository.findByBuildingIdAndLevel(building.getId(), floorLevel)
            .orElseGet(() -> {
                Floor newFloor = Floor.builder()
                    .name(floorName)
                    .level(floorLevel)
                    .build();
                building.addFloor(newFloor);
                return floorRepository.save(newFloor);
            });

        // Delete existing floor path if any
        if (floor.getFloorPath() != null) {
            floorPathRepository.delete(floor.getFloorPath());
        }

        // Create new floor path
        FloorPath floorPath = FloorPath.builder()
            .build();

        floor.updateFloorPath(floorPath);

        // Add segments
        List<Map<String, Object>> segments = (List<Map<String, Object>>) floorPathData.get("segments");
        for (Map<String, Object> segmentData : segments) {
            PathSegment segment = createPathSegment(segmentData);
            floorPath.addSegment(segment);
        }

        // Set bounds
        Map<String, Object> bounds = (Map<String, Object>) floorPathData.get("bounds");
        floorPath.updateBounds(
            ((Number) bounds.get("min_x")).doubleValue(),
            ((Number) bounds.get("max_x")).doubleValue(),
            ((Number) bounds.get("min_y")).doubleValue(),
            ((Number) bounds.get("max_y")).doubleValue()
        );

        floorPath.updateTotalDistance(((Number) floorPathData.get("total_distance")).doubleValue());

        floorPathRepository.save(floorPath);
    }

    private void applyVerticalPassage(Building building, Map<String, Object> passageData) {
        String type = (String) passageData.get("type");
        int fromFloorLevel = ((Number) passageData.get("from_floor_level")).intValue();
        int toFloorLevel = ((Number) passageData.get("to_floor_level")).intValue();

        // Find floors
        Floor fromFloor = floorRepository.findByBuildingIdAndLevel(building.getId(), fromFloorLevel)
            .orElseThrow(() -> new BusinessException(ErrorCode.FLOOR_NOT_FOUND,
                "From floor not found: " + fromFloorLevel));

        Floor toFloor = floorRepository.findByBuildingIdAndLevel(building.getId(), toFloorLevel)
            .orElseThrow(() -> new BusinessException(ErrorCode.FLOOR_NOT_FOUND,
                "To floor not found: " + toFloorLevel));

        // Create vertical passage
        VerticalPassage passage = VerticalPassage.builder()
            .type(PassageType.valueOf(type))
            .fromFloor(fromFloor)
            .toFloor(toFloor)
            .build();

        building.addVerticalPassage(passage);

        // Add segments
        List<Map<String, Object>> segments = (List<Map<String, Object>>) passageData.get("segments");
        for (Map<String, Object> segmentData : segments) {
            PathSegment segment = createPathSegment(segmentData);
            passage.addSegment(segment);
        }

        // Set entry/exit points
        Map<String, Object> entryPoint = (Map<String, Object>) passageData.get("entry_point");
        Map<String, Object> exitPoint = (Map<String, Object>) passageData.get("exit_point");

        passage.updateEntryPoint(
            ((Number) entryPoint.get("x")).doubleValue(),
            ((Number) entryPoint.get("y")).doubleValue(),
            ((Number) entryPoint.get("z")).doubleValue()
        );

        passage.updateExitPoint(
            ((Number) exitPoint.get("x")).doubleValue(),
            ((Number) exitPoint.get("y")).doubleValue(),
            ((Number) exitPoint.get("z")).doubleValue()
        );

        verticalPassageRepository.save(passage);
    }

    private PathSegment createPathSegment(Map<String, Object> data) {
        int sequenceOrder = ((Number) data.get("sequence_order")).intValue();

        Map<String, Object> startData = (Map<String, Object>) data.get("start_point");
        Map<String, Object> endData = (Map<String, Object>) data.get("end_point");

        Point3D startPoint = Point3D.builder()
            .x(((Number) startData.get("x")).doubleValue())
            .y(((Number) startData.get("y")).doubleValue())
            .z(((Number) startData.get("z")).doubleValue())
            .build();

        Point3D endPoint = Point3D.builder()
            .x(((Number) endData.get("x")).doubleValue())
            .y(((Number) endData.get("y")).doubleValue())
            .z(((Number) endData.get("z")).doubleValue())
            .build();

        return PathSegment.builder()
            .sequenceOrder(sequenceOrder)
            .startPoint(startPoint)
            .endPoint(endPoint)
            .build();
    }
}
