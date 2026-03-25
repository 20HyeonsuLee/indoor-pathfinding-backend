package com.koreatech.indoor_pathfinding.modules.floor.application.command;

import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;
import com.koreatech.indoor_pathfinding.modules.floor.domain.repository.FloorRepository;
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
public class FloorDeleter {

    private final FloorRepository floorRepository;
    private final PathEdgeRepository pathEdgeRepository;
    private final PathNodeRepository pathNodeRepository;

    public void delete(final UUID floorId) {
        final Floor floor = floorRepository.findById(floorId)
            .orElseThrow(() -> new BusinessException(ErrorCode.FLOOR_NOT_FOUND));

        // FK 순서: edges → nodes → floor
        pathEdgeRepository.deleteByFloorId(floorId);
        pathNodeRepository.deleteByFloorId(floorId);

        floorRepository.delete(floor);
        log.info("Floor deleted: {} ({})", floor.getName(), floorId);
    }
}
