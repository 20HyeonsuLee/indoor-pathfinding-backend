package com.koreatech.indoor_pathfinding.modules.floor.application.command;

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
public class FloorDeleter {

    private final FloorRepository floorRepository;

    public void delete(UUID floorId) {
        if (!floorRepository.existsById(floorId)) {
            throw new BusinessException(ErrorCode.FLOOR_NOT_FOUND);
        }
        floorRepository.deleteById(floorId);
    }
}
