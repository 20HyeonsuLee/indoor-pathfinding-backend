package com.koreatech.indoor_pathfinding.e2e;

import com.koreatech.indoor_pathfinding.modules.building.domain.model.Building;
import com.koreatech.indoor_pathfinding.modules.building.domain.model.BuildingStatus;
import com.koreatech.indoor_pathfinding.modules.building.domain.repository.BuildingRepository;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ScanSession;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ScanStatus;
import com.koreatech.indoor_pathfinding.modules.scan.domain.repository.ScanSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ScanControllerE2ETest extends BaseE2ETest {

    @Autowired
    private BuildingRepository buildingRepository;

    @Autowired
    private ScanSessionRepository scanSessionRepository;

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
    @DisplayName("POST /api/v1/buildings/{buildingId}/scans")
    class UploadScanFile {

        @Test
        @DisplayName("should upload scan file")
        void uploadScanFile_WithValidFile_ReturnsCreated() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "test_scan.db",
                    "application/octet-stream",
                    "test scan content".getBytes()
            );

            mockMvc.perform(multipart("/api/v1/buildings/{buildingId}/scans", testBuilding.getId())
                            .file(file))
                    .andDo(print())
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").exists())
                    .andExpect(jsonPath("$.fileName").value("test_scan.db"))
                    .andExpect(jsonPath("$.status").value("UPLOADED"));
        }

        @Test
        @DisplayName("should return 404 when building not found")
        void uploadScanFile_WhenBuildingNotFound_ReturnsNotFound() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "test_scan.db",
                    "application/octet-stream",
                    "test scan content".getBytes()
            );

            mockMvc.perform(multipart("/api/v1/buildings/{buildingId}/scans", UUID.randomUUID())
                            .file(file))
                    .andDo(print())
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 400 when file is empty")
        void uploadScanFile_WithEmptyFile_ReturnsBadRequest() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "empty.db",
                    "application/octet-stream",
                    new byte[0]
            );

            mockMvc.perform(multipart("/api/v1/buildings/{buildingId}/scans", testBuilding.getId())
                            .file(file))
                    .andDo(print())
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/buildings/{buildingId}/scans")
    class GetScanSessions {

        @Test
        @DisplayName("should return empty list when no scan sessions exist")
        void getScanSessions_WhenEmpty_ReturnsEmptyList() throws Exception {
            mockMvc.perform(get("/api/v1/buildings/{buildingId}/scans", testBuilding.getId()))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$").isEmpty());
        }

        @Test
        @DisplayName("should return all scan sessions for building")
        void getScanSessions_WhenSessionsExist_ReturnsAll() throws Exception {
            createTestScanSession(testBuilding, "scan1.db", ScanStatus.UPLOADED);
            createTestScanSession(testBuilding, "scan2.db", ScanStatus.COMPLETED);

            mockMvc.perform(get("/api/v1/buildings/{buildingId}/scans", testBuilding.getId()))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$", hasSize(2)));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/buildings/{buildingId}/scans/{sessionId}")
    class GetScanSession {

        @Test
        @DisplayName("should return scan session details")
        void getScanSession_WhenExists_ReturnsDetail() throws Exception {
            ScanSession session = createTestScanSession(testBuilding, "test_scan.db", ScanStatus.UPLOADED);

            mockMvc.perform(get("/api/v1/buildings/{buildingId}/scans/{sessionId}",
                            testBuilding.getId(), session.getId()))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(session.getId().toString()))
                    .andExpect(jsonPath("$.fileName").value("test_scan.db"))
                    .andExpect(jsonPath("$.status").value("UPLOADED"));
        }

        @Test
        @DisplayName("should return 404 when scan session not found")
        void getScanSession_WhenNotExists_ReturnsNotFound() throws Exception {
            mockMvc.perform(get("/api/v1/buildings/{buildingId}/scans/{sessionId}",
                            testBuilding.getId(), UUID.randomUUID()))
                    .andDo(print())
                    .andExpect(status().isNotFound());
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
