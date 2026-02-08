package com.koreatech.indoor_pathfinding.modules.building.application.command;

import com.koreatech.indoor_pathfinding.modules.building.domain.repository.BuildingRepository;
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
public class BuildingDeleter {

    private final BuildingRepository buildingRepository;

    public void delete(UUID id) {
        if (!buildingRepository.existsById(id)) {
            throw new BusinessException(ErrorCode.BUILDING_NOT_FOUND);
        }
        buildingRepository.deleteById(id);
    }
}
