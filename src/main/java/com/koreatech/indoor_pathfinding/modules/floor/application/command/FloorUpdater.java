package com.koreatech.indoor_pathfinding.modules.floor.application.command;

import com.koreatech.indoor_pathfinding.modules.floor.application.dto.request.FloorUpdateRequest;
import com.koreatech.indoor_pathfinding.modules.floor.application.dto.response.FloorResponse;
import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;
import com.koreatech.indoor_pathfinding.modules.floor.domain.repository.FloorRepository;
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
public class FloorUpdater {

    private final FloorRepository floorRepository;

    public FloorResponse update(UUID floorId, FloorUpdateRequest request) {
        Floor floor = floorRepository.findById(floorId)
            .orElseThrow(() -> new BusinessException(ErrorCode.FLOOR_NOT_FOUND));

        floor.updateInfo(request.name(), request.height());

        return FloorResponse.from(floor);
    }
}
