package com.koreatech.indoor_pathfinding.modules.pathprocessing.application.query;

import com.koreatech.indoor_pathfinding.modules.pathprocessing.infrastructure.external.PathProcessingClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PreviewImageReader {

    private final PathProcessingClient pathProcessingClient;

    public byte[] getPreviewImage(String jobId, String imageType) {
        return pathProcessingClient.getPreviewImage(jobId, imageType).block();
    }
}
