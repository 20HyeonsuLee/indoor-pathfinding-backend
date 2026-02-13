package com.koreatech.indoor_pathfinding.modules.localization.infrastructure.external;

import com.koreatech.indoor_pathfinding.shared.exception.BusinessException;
import com.koreatech.indoor_pathfinding.shared.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class VpsClient {

    private final WebClient webClient;

    public VpsClient(@Value("${vps-service.base-url:http://localhost:5000}") final String baseUrl) {
        this.webClient = WebClient.builder()
            .baseUrl(baseUrl)
            .build();
    }

    public Map<String, Object> processSlam(final String buildingId) {
        log.info("Starting SLAM processing for building: {}", buildingId);

        try {
            final Map<String, Object> response = webClient.post()
                .uri("/api/slam/process")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("building_id", buildingId))
                .retrieve()
                .bodyToMono(Map.class)
                .block();

            if (response == null) {
                throw new BusinessException(ErrorCode.VPS_SERVICE_ERROR,
                    "Empty response from VPS service");
            }

            log.info("SLAM processing started for building: {}, map_id: {}", buildingId, response.get("map_id"));
            return response;

        } catch (WebClientResponseException exception) {
            log.error("Failed to start SLAM processing: {}", exception.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.VPS_SERVICE_ERROR,
                "Failed to start SLAM processing: " + exception.getMessage());
        }
    }

    public Map<String, Object> localize(final String mapId, final List<MultipartFile> images) {
        log.info("Localizing in map: {} with {} images", mapId, images.size());

        final MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("map_id", mapId);

        for (final MultipartFile image : images) {
            try {
                final String filename = image.getOriginalFilename() != null
                    ? image.getOriginalFilename()
                    : "image.jpg";

                builder.part("images", new ByteArrayResource(image.getBytes()) {
                    @Override
                    public String getFilename() {
                        return filename;
                    }
                }).contentType(MediaType.IMAGE_JPEG);

            } catch (IOException exception) {
                throw new BusinessException(ErrorCode.LOCALIZATION_FAILED,
                    "Failed to read image file: " + exception.getMessage());
            }
        }

        try {
            final Map<String, Object> response = webClient.post()
                .uri("/api/localize")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(Map.class)
                .block();

            if (response == null) {
                throw new BusinessException(ErrorCode.LOCALIZATION_FAILED,
                    "Empty response from VPS service");
            }

            log.info("Localization result - confidence: {}", response.get("confidence"));
            return response;

        } catch (WebClientResponseException exception) {
            log.error("Failed to localize: {}", exception.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.LOCALIZATION_FAILED,
                "Failed to localize: " + exception.getMessage());
        }
    }

    public Map<String, Object> getSlamStatus(final String buildingId) {
        log.debug("Getting SLAM status for building: {}", buildingId);

        try {
            final Map<String, Object> response = webClient.get()
                .uri("/api/slam/status/{buildingId}", buildingId)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

            if (response == null) {
                throw new BusinessException(ErrorCode.VPS_SERVICE_ERROR,
                    "Empty response from VPS service");
            }

            return response;

        } catch (WebClientResponseException exception) {
            log.error("Failed to get SLAM status: {}", exception.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.VPS_SERVICE_ERROR,
                "Failed to get SLAM status: " + exception.getMessage());
        }
    }

    public Map<String, Object> getMapMetadata(final String buildingId) {
        log.debug("Getting map metadata for building: {}", buildingId);

        try {
            final Map<String, Object> response = webClient.get()
                .uri("/api/slam/maps/{buildingId}/metadata", buildingId)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

            if (response == null) {
                throw new BusinessException(ErrorCode.VPS_SERVICE_ERROR,
                    "Empty response from VPS service");
            }

            return response;

        } catch (WebClientResponseException exception) {
            log.error("Failed to get map metadata: {}", exception.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.VPS_SERVICE_ERROR,
                "Failed to get map metadata: " + exception.getMessage());
        }
    }
}
