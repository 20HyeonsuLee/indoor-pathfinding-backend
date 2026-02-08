package com.koreatech.indoor_pathfinding.modules.pathprocessing.infrastructure.external;

import com.koreatech.indoor_pathfinding.modules.pathprocessing.application.dto.response.ProcessingStatusResponse;
import com.koreatech.indoor_pathfinding.shared.exception.BusinessException;
import com.koreatech.indoor_pathfinding.shared.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.Map;

@Slf4j
@Component
public class PathProcessingClient {

    private final WebClient webClient;

    public PathProcessingClient(@Value("${path-service.base-url:http://localhost:8001}") String baseUrl) {
        this.webClient = WebClient.builder()
            .baseUrl(baseUrl)
            .build();
    }

    public String uploadFile(Path filePath) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new FileSystemResource(filePath.toFile()));

        try {
            Map<String, Object> response = webClient.post()
                .uri("/api/v1/upload")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(Map.class)
                .block();

            if (response == null || !response.containsKey("file_id")) {
                throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Invalid response from path service");
            }

            String fileId = (String) response.get("file_id");
            log.info("File uploaded to path service with id: {}", fileId);
            return fileId;

        } catch (WebClientResponseException e) {
            log.error("Failed to upload file to path service: {}", e.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                "Failed to upload file: " + e.getMessage());
        }
    }

    public String startProcessing(String fileId) {
        log.info("Starting processing for file: {}", fileId);

        try {
            Map<String, Object> response = webClient.post()
                .uri("/api/v1/process/{fileId}", fileId)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

            if (response == null || !response.containsKey("job_id")) {
                throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Invalid response from path service");
            }

            String jobId = (String) response.get("job_id");
            log.info("Processing started with job id: {}", jobId);
            return jobId;

        } catch (WebClientResponseException e) {
            log.error("Failed to start processing: {}", e.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                "Failed to start processing: " + e.getMessage());
        }
    }

    public ProcessingStatusResponse getJobStatus(String jobId) {
        log.debug("Getting job status: {}", jobId);

        try {
            Map<String, Object> response = webClient.get()
                .uri("/api/v1/jobs/{jobId}", jobId)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

            if (response == null) {
                throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Invalid response from path service");
            }

            return new ProcessingStatusResponse(
                (String) response.get("job_id"),
                (String) response.get("status"),
                response.get("progress") != null ? ((Number) response.get("progress")).intValue() : 0,
                (String) response.get("message"),
                (String) response.get("created_at"),
                (String) response.get("completed_at"),
                (String) response.get("error")
            );

        } catch (WebClientResponseException e) {
            log.error("Failed to get job status: {}", e.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                "Failed to get job status: " + e.getMessage());
        }
    }

    public Map<String, Object> getJobResult(String jobId) {
        log.info("Getting job result: {}", jobId);

        try {
            Map<String, Object> response = webClient.get()
                .uri("/api/v1/jobs/{jobId}/result", jobId)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

            if (response == null) {
                throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Invalid response from path service");
            }

            return response;

        } catch (WebClientResponseException e) {
            log.error("Failed to get job result: {}", e.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                "Failed to get job result: " + e.getMessage());
        }
    }

    public Mono<byte[]> getPreviewImage(String jobId, String imageType) {
        return webClient.get()
            .uri("/api/v1/preview/{jobId}/{imageType}", jobId, imageType)
            .retrieve()
            .bodyToMono(byte[].class)
            .onErrorMap(WebClientResponseException.class, e ->
                new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Failed to get preview image: " + e.getMessage())
            );
    }

    public boolean isHealthy() {
        try {
            Map<String, Object> response = webClient.get()
                .uri("/health")
                .retrieve()
                .bodyToMono(Map.class)
                .block();

            return response != null && "healthy".equals(response.get("status"));
        } catch (Exception e) {
            log.warn("Path service health check failed: {}", e.getMessage());
            return false;
        }
    }
}
