package com.koreatech.indoor_pathfinding.modules.pathfinding.application.query;

import com.koreatech.indoor_pathfinding.modules.floor.domain.repository.FloorRepository;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.response.FloorGraphResponse;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.response.PathEdgeResponse;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.response.PathNodeResponse;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.repository.PathEdgeRepository;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.repository.PathNodeRepository;
import com.koreatech.indoor_pathfinding.shared.exception.BusinessException;
import com.koreatech.indoor_pathfinding.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GraphReader {

    private final PathNodeRepository pathNodeRepository;
    private final PathEdgeRepository pathEdgeRepository;
    private final FloorRepository floorRepository;

    public FloorGraphResponse findGraphByFloorId(UUID floorId) {
        floorRepository.findById(floorId)
            .orElseThrow(() -> new BusinessException(ErrorCode.FLOOR_NOT_FOUND));

        List<PathNodeResponse> nodes = pathNodeRepository.findByFloorId(floorId).stream()
            .map(PathNodeResponse::from)
            .toList();

        List<PathEdgeResponse> edges = pathEdgeRepository.findByFloorId(floorId).stream()
            .map(PathEdgeResponse::from)
            .toList();

        return new FloorGraphResponse(floorId, nodes, edges);
    }
}
