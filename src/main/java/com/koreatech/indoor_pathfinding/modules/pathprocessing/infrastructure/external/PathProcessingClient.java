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

    public PathProcessingClient(@Value("${path-service.base-url:http://localhost:8000}") String baseUrl) {
        this.webClient = WebClient.builder()
            .baseUrl(baseUrl)
            .codecs(configurer -> configurer
                .defaultCodecs()
                .maxInMemorySize(16 * 1024 * 1024))  // 16MB
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

    /**
     * 공유 볼륨의 .db 파일 경로를 전달하여 PLY 추출을 요청한다.
     * 반환값: cache_key (PLY 다운로드에 사용)
     */
    public String extractPointcloudPly(String fileId) {
        log.info("Requesting PLY extraction for fileId: {}", fileId);
        try {
            Map<String, Object> response = webClient.post()
                .uri("/api/v1/pointcloud/extract")
                .bodyValue(Map.of("file_id", fileId))
                .retrieve()
                .bodyToMono(Map.class)
                .block();

            String cacheKey = (String) response.get("cache_key");
            log.info("PLY extraction completed, cache_key: {}", cacheKey);
            return cacheKey;
        } catch (WebClientResponseException e) {
            log.warn("PLY extraction failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                "PLY extraction failed: " + e.getMessage());
        }
    }

    /**
     * 여러 .db 파일을 rtabmap-reprocess로 병합한다.
     * 반환값: merge job_id (상태 폴링에 사용)
     */
    public String mergeChunks(java.util.List<String> chunkFilePaths, String outputPath) {
        log.info("Requesting merge of {} chunks", chunkFilePaths.size());
        try {
            Map<String, Object> response = webClient.post()
                .uri("/api/v1/merge")
                .bodyValue(Map.of(
                    "chunk_file_paths", chunkFilePaths,
                    "output_path", outputPath
                ))
                .retrieve()
                .bodyToMono(Map.class)
                .block();

            String jobId = (String) response.get("job_id");
            log.info("Merge started with job_id: {}", jobId);
            return jobId;
        } catch (WebClientResponseException e) {
            log.error("Merge request failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.MERGE_FAILED,
                "Merge request failed: " + e.getMessage());
        }
    }

    /**
     * 병합 작업 상태를 조회한다.
     */
    public Map<String, Object> getMergeStatus(String mergeJobId) {
        try {
            return webClient.get()
                .uri("/api/v1/merge/{jobId}", mergeJobId)
                .retrieve()
                .bodyToMono(Map.class)
                .block();
        } catch (WebClientResponseException e) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                "Failed to get merge status: " + e.getMessage());
        }
    }

    public byte[] getPointcloudPly(String cacheKey) {
        try {
            return webClient.get()
                .uri("/api/v1/pointcloud/{cacheKey}/ply", cacheKey)
                .retrieve()
                .bodyToMono(byte[].class)
                .block();
        } catch (WebClientResponseException e) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                "Failed to get PLY file: " + e.getMessage());
        }
    }

}
