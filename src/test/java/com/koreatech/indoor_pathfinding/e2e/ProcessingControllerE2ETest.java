package com.koreatech.indoor_pathfinding.e2e;

import com.koreatech.indoor_pathfinding.modules.building.domain.model.Building;
import com.koreatech.indoor_pathfinding.modules.building.domain.model.BuildingStatus;
import com.koreatech.indoor_pathfinding.modules.building.domain.repository.BuildingRepository;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.application.dto.request.ProcessingStartRequest;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.application.dto.response.ProcessingStatusResponse;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.infrastructure.external.PathProcessingClient;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ScanSession;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ScanStatus;
import com.koreatech.indoor_pathfinding.modules.scan.domain.repository.ScanSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProcessingControllerE2ETest extends BaseE2ETest {

    @Autowired
    private BuildingRepository buildingRepository;

    @Autowired
    private ScanSessionRepository scanSessionRepository;

    @MockitoBean
    private PathProcessingClient pathProcessingClient;

    private Building testBuilding;

    @BeforeEach
    void setUp() {
        testBuilding = buildingRepository.save(
                Building.builder()
                        .name("Test Building")
                        .description("Test Description")
                        .status(BuildingStatus.DRAFT)
                        .build()
        );
    }

    @AfterEach
    void tearDown() {
        scanSessionRepository.deleteAll();
        buildingRepository.deleteAll();
    }

    @Nested
    @DisplayName("POST /api/v1/buildings/{buildingId}/process")
    class StartProcessing {

        @Test
        @DisplayName("should start processing for valid scan session")
        void startProcessing_WithValidSession_ReturnsOk() throws Exception {
            ScanSession session = createTestScanSession(testBuilding, "test_scan.db", ScanStatus.UPLOADED);

            when(pathProcessingClient.uploadFile(any(Path.class))).thenReturn("file-123");
            when(pathProcessingClient.startProcessing("file-123")).thenReturn("job-456");

            ProcessingStartRequest request = new ProcessingStartRequest(session.getId());

            mockMvc.perform(post("/api/v1/buildings/{buildingId}/process", testBuilding.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.jobId").value("job-456"))
                    .andExpect(jsonPath("$.sessionId").value(session.getId().toString()));
        }

        @Test
        @DisplayName("should return 400 when session is currently being processed")
        void startProcessing_WithInvalidSessionState_ReturnsBadRequest() throws Exception {
            ScanSession session = createTestScanSession(testBuilding, "test_scan.db", ScanStatus.EXTRACTING);

            ProcessingStartRequest request = new ProcessingStartRequest(session.getId());

            mockMvc.perform(post("/api/v1/buildings/{buildingId}/process", testBuilding.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andDo(print())
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 404 when session not found")
        void startProcessing_WhenSessionNotFound_ReturnsNotFound() throws Exception {
            ProcessingStartRequest request = new ProcessingStartRequest(UUID.randomUUID());

            mockMvc.perform(post("/api/v1/buildings/{buildingId}/process", testBuilding.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andDo(print())
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/buildings/{buildingId}/process/status")
    class GetProcessingStatus {

        @Test
        @DisplayName("should return processing status")
        void getProcessingStatus_WithValidSession_ReturnsStatus() throws Exception {
            ScanSession session = createTestScanSession(testBuilding, "test_scan.db", ScanStatus.EXTRACTING);

            // Start processing first to set up the job mapping
            when(pathProcessingClient.uploadFile(any(Path.class))).thenReturn("file-123");
            when(pathProcessingClient.startProcessing("file-123")).thenReturn("job-456");

            // Update session to UPLOADED for processing start
            session.updateStatus(ScanStatus.UPLOADED);
            scanSessionRepository.save(session);

            ProcessingStartRequest startRequest = new ProcessingStartRequest(session.getId());
            mockMvc.perform(post("/api/v1/buildings/{buildingId}/process", testBuilding.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(startRequest)));

            // Now mock status check
            ProcessingStatusResponse statusResponse = new ProcessingStatusResponse(
                    "job-456", "processing", 50, "Extracting paths", "2024-01-01T00:00:00", null, null
            );
            when(pathProcessingClient.getJobStatus("job-456")).thenReturn(statusResponse);

            mockMvc.perform(get("/api/v1/buildings/{buildingId}/process/status", testBuilding.getId())
                            .param("sessionId", session.getId().toString()))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.jobId").value("job-456"))
                    .andExpect(jsonPath("$.status").value("processing"))
                    .andExpect(jsonPath("$.progress").value(50));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/buildings/{buildingId}/preview/{jobId}/{imageType}")
    class GetPreviewImage {

        @Test
        @DisplayName("should return preview image")
        void getPreviewImage_WhenExists_ReturnsImage() throws Exception {
            byte[] testImage = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}; // PNG header bytes
            when(pathProcessingClient.getPreviewImage("job-123", "trajectory"))
                    .thenReturn(Mono.just(testImage));

            mockMvc.perform(get("/api/v1/buildings/{buildingId}/preview/{jobId}/{imageType}",
                            testBuilding.getId(), "job-123", "trajectory"))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.IMAGE_PNG));
        }
    }

    private ScanSession createTestScanSession(Building building, String fileName, ScanStatus status) {
        ScanSession session = ScanSession.builder()
                .fileName(fileName)
                .filePath("/test/path/" + fileName)
                .fileSize(1024L)
                .status(status)
                .build();
        building.addScanSession(session);
        return scanSessionRepository.save(session);
    }
}
