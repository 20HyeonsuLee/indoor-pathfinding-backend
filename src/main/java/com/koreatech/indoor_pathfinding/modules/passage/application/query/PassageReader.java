package com.koreatech.indoor_pathfinding.modules.passage.application.query;

import com.koreatech.indoor_pathfinding.modules.passage.application.dto.response.VerticalPassageDetailResponse;
import com.koreatech.indoor_pathfinding.modules.passage.application.dto.response.VerticalPassageResponse;
import com.koreatech.indoor_pathfinding.modules.passage.domain.model.PassageType;
import com.koreatech.indoor_pathfinding.modules.passage.domain.model.VerticalPassage;
import com.koreatech.indoor_pathfinding.modules.passage.domain.repository.VerticalPassageRepository;
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
public class PassageReader {

    private final VerticalPassageRepository verticalPassageRepository;

    public List<VerticalPassageResponse> findByBuildingId(UUID buildingId) {
        return verticalPassageRepository.findByBuildingId(buildingId).stream()
            .map(VerticalPassageResponse::from)
            .toList();
    }

    public List<VerticalPassageResponse> findByBuildingIdAndType(UUID buildingId, PassageType type) {
        return verticalPassageRepository.findByBuildingIdAndType(buildingId, type).stream()
            .map(VerticalPassageResponse::from)
            .toList();
    }

    public VerticalPassageDetailResponse findById(UUID passageId) {
        VerticalPassage passage = verticalPassageRepository.findByIdWithSegments(passageId)
            .orElseThrow(() -> new BusinessException(ErrorCode.PATH_NOT_FOUND));

        return VerticalPassageDetailResponse.from(passage);
    }
}
