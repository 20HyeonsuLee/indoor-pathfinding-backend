package com.koreatech.indoor_pathfinding.modules.localization.application.service;

import com.koreatech.indoor_pathfinding.modules.localization.application.dto.response.LocalizeResponse;
import com.koreatech.indoor_pathfinding.modules.localization.application.dto.response.MapMetadataResponse;
import com.koreatech.indoor_pathfinding.modules.localization.application.dto.response.SlamStatusResponse;
import com.koreatech.indoor_pathfinding.modules.localization.infrastructure.external.VpsClient;
import com.koreatech.indoor_pathfinding.shared.exception.BusinessException;
import com.koreatech.indoor_pathfinding.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocalizationService {

    private final VpsClient vpsClient;

    public LocalizeResponse localize(final UUID buildingId, final List<MultipartFile> images) {
        if (images.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "At least one image is required");
        }

        final MapMetadataResponse metadata = fetchMapMetadata(buildingId);
        final Map<String, Object> result = vpsClient.localize(metadata.mapId(), images);
        return LocalizeResponse.from(result);
    }

    public SlamStatusResponse getSlamStatus(final UUID buildingId) {
        final Map<String, Object> result = vpsClient.getSlamStatus(buildingId.toString());
        return SlamStatusResponse.from(result);
    }

    public MapMetadataResponse getMapMetadata(final UUID buildingId) {
        return fetchMapMetadata(buildingId);
    }

    private MapMetadataResponse fetchMapMetadata(final UUID buildingId) {
        final Map<String, Object> result = vpsClient.getMapMetadata(buildingId.toString());
        return MapMetadataResponse.from(result);
    }
}
