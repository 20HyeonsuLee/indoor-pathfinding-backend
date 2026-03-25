package com.koreatech.indoor_pathfinding.modules.pathprocessing.application.command;

import com.koreatech.indoor_pathfinding.modules.building.domain.model.Building;
import com.koreatech.indoor_pathfinding.modules.building.domain.model.BuildingStatus;
import com.koreatech.indoor_pathfinding.modules.building.domain.repository.BuildingRepository;
import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;
import com.koreatech.indoor_pathfinding.modules.floor.domain.model.FloorPath;
import com.koreatech.indoor_pathfinding.modules.floor.domain.repository.FloorPathRepository;
import com.koreatech.indoor_pathfinding.modules.floor.domain.repository.FloorRepository;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.domain.model.PathSegment;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.domain.model.Point3D;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.infrastructure.external.PathProcessingClient;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.MergedScan;
import com.koreatech.indoor_pathfinding.modules.scan.domain.repository.MergedScanRepository;
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
    private final BuildingRepository buildingRepository;
    private final FloorRepository floorRepository;
    private final FloorPathRepository floorPathRepository;
    private final MergedScanRepository mergedScanRepository;

    public void applyToFloor(final UUID floorId, final String jobId) {
        log.info("Applying processing result for floor: {}", floorId);

        final Map<String, Object> result = pathProcessingClient.getJobResult(jobId);

        final Floor floor = floorRepository.findById(floorId)
            .orElseThrow(() -> new BusinessException(ErrorCode.FLOOR_NOT_FOUND));

        // 기존 FloorPath 삭제
        if (floor.getFloorPath() != null) {
            floorPathRepository.delete(floor.getFloorPath());
            floor.updateFloorPath(null);
        }

        // 층별 처리이므로 floor_paths의 첫 번째 결과 적용
        final List<Map<String, Object>> floorPaths =
            (List<Map<String, Object>>) result.get("floor_paths");

        if (floorPaths != null && !floorPaths.isEmpty()) {
            applyFloorPath(floor, floorPaths.getFirst());
        }

        // MergedScan 업데이트
        final int totalNodes = ((Number) result.get("total_nodes")).intValue();
        final double totalDistance = ((Number) result.get("total_distance")).doubleValue();
        final String previewPath = (String) result.get("preview_image_path");

        final MergedScan mergedScan = mergedScanRepository.findByFloorId(floorId)
            .orElseThrow(() -> new BusinessException(ErrorCode.MERGED_SCAN_NOT_FOUND));

        mergedScan.markCompleted(totalNodes, totalDistance, previewPath);
        mergedScanRepository.save(mergedScan);

        // Building 상태 업데이트
        final Building building = floor.getBuilding();
        building.updateStatus(BuildingStatus.ACTIVE);
        buildingRepository.save(building);

        log.info("Applied processing result for floor: {}", floorId);
    }

    public void updateFloorPly(final UUID floorId, final String plyKey) {
        final Floor floor = floorRepository.findById(floorId)
            .orElseThrow(() -> new BusinessException(ErrorCode.FLOOR_NOT_FOUND));

        floor.updatePlyFileId(plyKey);
        floorRepository.save(floor);

        final MergedScan mergedScan = mergedScanRepository.findByFloorId(floorId)
            .orElseThrow(() -> new BusinessException(ErrorCode.MERGED_SCAN_NOT_FOUND));

        mergedScan.updatePlyFileId(plyKey);
        mergedScanRepository.save(mergedScan);

        log.info("Updated PLY for floor {} (key={})", floorId, plyKey);
    }

    private void applyFloorPath(final Floor floor, final Map<String, Object> floorPathData) {
        final FloorPath floorPath = FloorPath.builder().build();
        floor.updateFloorPath(floorPath);

        final List<Map<String, Object>> segments =
            (List<Map<String, Object>>) floorPathData.get("segments");

        for (Map<String, Object> segmentData : segments) {
            floorPath.addSegment(createPathSegment(segmentData));
        }

        final Map<String, Object> bounds =
            (Map<String, Object>) floorPathData.get("bounds");

        floorPath.updateBounds(
            ((Number) bounds.get("min_x")).doubleValue(),
            ((Number) bounds.get("max_x")).doubleValue(),
            ((Number) bounds.get("min_y")).doubleValue(),
            ((Number) bounds.get("max_y")).doubleValue()
        );

        floorPath.updateTotalDistance(
            ((Number) floorPathData.get("total_distance")).doubleValue());

        floorPathRepository.save(floorPath);
    }

    private PathSegment createPathSegment(final Map<String, Object> data) {
        final int sequenceOrder = ((Number) data.get("sequence_order")).intValue();
        final Map<String, Object> startData = (Map<String, Object>) data.get("start_point");
        final Map<String, Object> endData = (Map<String, Object>) data.get("end_point");

        return PathSegment.builder()
            .sequenceOrder(sequenceOrder)
            .startPoint(Point3D.builder()
                .x(((Number) startData.get("x")).doubleValue())
                .y(((Number) startData.get("y")).doubleValue())
                .z(((Number) startData.get("z")).doubleValue())
                .build())
            .endPoint(Point3D.builder()
                .x(((Number) endData.get("x")).doubleValue())
                .y(((Number) endData.get("y")).doubleValue())
                .z(((Number) endData.get("z")).doubleValue())
                .build())
            .build();
    }
}
