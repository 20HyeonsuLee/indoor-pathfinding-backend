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

        // 그래프 자동 생성 건너뜀 — 수동 노드 배치로 전환

        log.info("Applied processing result for session: {} (floors created, graph skipped)", sessionId);
    }

    /**
     * 전체 PLY에서 층별 PLY를 추출한다.
     * apply() 이후 트랜잭션 내에서 호출하여 세그먼트 lazy 로딩이 가능하도록 한다.
     */
    public void extractFloorPlys(UUID buildingId, String wholePlyKey) {
        List<Floor> floors = floorRepository.findByBuildingIdOrderByLevelAsc(buildingId);
        log.info("Extracting per-floor PLY for {} floors (wholePly={})", floors.size(), wholePlyKey);

        for (Floor floor : floors) {
            try {
                double minZ = Double.MAX_VALUE;
                double maxZ = -Double.MAX_VALUE;

                if (floor.getFloorPath() != null) {
                    for (PathSegment seg : floor.getFloorPath().getSegments()) {
                        minZ = Math.min(minZ, Math.min(seg.getStartPoint().getZ(), seg.getEndPoint().getZ()));
                        maxZ = Math.max(maxZ, Math.max(seg.getStartPoint().getZ(), seg.getEndPoint().getZ()));
                    }
                }

                if (minZ == Double.MAX_VALUE) {
                    double h = floor.getHeight() != null ? floor.getHeight() : 3.0;
                    minZ = (floor.getLevel() - 1) * h;
                    maxZ = floor.getLevel() * h;
                }

                minZ -= 0.5;
                maxZ += 1.5;

                String key = pathProcessingClient.extractFloorPly(wholePlyKey, minZ, maxZ);
                floor.updatePlyFileId(key);
                floorRepository.save(floor);

                log.info("  Floor {} (L{}) PLY: z=[{},{}] key={}",
                    floor.getName(), floor.getLevel(),
                    String.format("%.1f", minZ), String.format("%.1f", maxZ), key);
            } catch (Exception e) {
                log.warn("  Floor {} PLY failed: {}", floor.getName(), e.getMessage());
            }
        }
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

        // Find or create floors
        Floor fromFloor = floorRepository.findByBuildingIdAndLevel(building.getId(), fromFloorLevel)
            .orElseGet(() -> {
                Floor f = Floor.builder().name(fromFloorLevel + "층").level(fromFloorLevel).build();
                building.addFloor(f);
                return floorRepository.save(f);
            });

        Floor toFloor = floorRepository.findByBuildingIdAndLevel(building.getId(), toFloorLevel)
            .orElseGet(() -> {
                Floor f = Floor.builder().name(toFloorLevel + "층").level(toFloorLevel).build();
                building.addFloor(f);
                return floorRepository.save(f);
            });

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
