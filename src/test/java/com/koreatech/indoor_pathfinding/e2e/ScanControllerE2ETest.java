package com.koreatech.indoor_pathfinding.e2e;

import com.koreatech.indoor_pathfinding.modules.building.domain.model.Building;
import com.koreatech.indoor_pathfinding.modules.building.domain.model.BuildingStatus;
import com.koreatech.indoor_pathfinding.modules.building.domain.repository.BuildingRepository;
import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;
import com.koreatech.indoor_pathfinding.modules.floor.domain.repository.FloorRepository;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ChunkStatus;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ScanChunk;
import com.koreatech.indoor_pathfinding.modules.scan.domain.repository.ScanChunkRepository;
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
    private FloorRepository floorRepository;

    @Autowired
    private ScanChunkRepository scanChunkRepository;

    private Building testBuilding;
    private Floor testFloor;

    @BeforeEach
    void setUp() {
        testBuilding = buildingRepository.save(
                Building.builder()
                        .name("Test Building")
                        .description("Test Description")
                        .status(BuildingStatus.DRAFT)
                        .build()
        );

        testFloor = Floor.builder()
                .name("1층")
                .level(1)
                .build();
        testBuilding.addFloor(testFloor);
        testFloor = floorRepository.save(testFloor);
    }

    @AfterEach
    void tearDown() {
        scanChunkRepository.deleteAll();
        floorRepository.deleteAll();
        buildingRepository.deleteAll();
    }

    @Nested
    @DisplayName("POST /api/v1/floors/{floorId}/scans/chunks")
    class UploadChunk {

        @Test
        @DisplayName("should upload chunk file")
        void uploadChunk_WithValidFile_ReturnsCreated() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "test_scan.db",
                    "application/octet-stream",
                    "test scan content".getBytes()
            );

            mockMvc.perform(multipart("/api/v1/floors/{floorId}/scans/chunks", testFloor.getId())
                            .file(file))
                    .andDo(print())
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").exists())
                    .andExpect(jsonPath("$.fileName").value("test_scan.db"))
                    .andExpect(jsonPath("$.status").value("UPLOADED"));
        }

        @Test
        @DisplayName("should return 404 when floor not found")
        void uploadChunk_WhenFloorNotFound_ReturnsNotFound() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "test_scan.db",
                    "application/octet-stream",
                    "test scan content".getBytes()
            );

            mockMvc.perform(multipart("/api/v1/floors/{floorId}/scans/chunks", UUID.randomUUID())
                            .file(file))
                    .andDo(print())
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 400 when file is empty")
        void uploadChunk_WithEmptyFile_ReturnsBadRequest() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "empty.db",
                    "application/octet-stream",
                    new byte[0]
            );

            mockMvc.perform(multipart("/api/v1/floors/{floorId}/scans/chunks", testFloor.getId())
                            .file(file))
                    .andDo(print())
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/floors/{floorId}/scans/chunks")
    class GetChunks {

        @Test
        @DisplayName("should return empty list when no chunks exist")
        void getChunks_WhenEmpty_ReturnsEmptyList() throws Exception {
            mockMvc.perform(get("/api/v1/floors/{floorId}/scans/chunks", testFloor.getId()))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$").isEmpty());
        }

        @Test
        @DisplayName("should return all chunks for floor")
        void getChunks_WhenChunksExist_ReturnsAll() throws Exception {
            createTestChunk(testFloor, "scan1.db", 1);
            createTestChunk(testFloor, "scan2.db", 2);

            mockMvc.perform(get("/api/v1/floors/{floorId}/scans/chunks", testFloor.getId()))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$", hasSize(2)));
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/floors/{floorId}/scans/chunks/{chunkId}")
    class DeleteChunk {

        @Test
        @DisplayName("should delete chunk")
        void deleteChunk_WhenExists_ReturnsNoContent() throws Exception {
            ScanChunk chunk = createTestChunk(testFloor, "test_scan.db", 1);

            mockMvc.perform(delete("/api/v1/floors/{floorId}/scans/chunks/{chunkId}",
                            testFloor.getId(), chunk.getId()))
                    .andDo(print())
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("should return 404 when chunk not found")
        void deleteChunk_WhenNotExists_ReturnsNotFound() throws Exception {
            mockMvc.perform(delete("/api/v1/floors/{floorId}/scans/chunks/{chunkId}",
                            testFloor.getId(), UUID.randomUUID()))
                    .andDo(print())
                    .andExpect(status().isNotFound());
        }
    }

    private ScanChunk createTestChunk(Floor floor, String fileName, int order) {
        ScanChunk chunk = ScanChunk.builder()
                .fileName(fileName)
                .filePath("/test/path/" + fileName)
                .fileSize(1024L)
                .status(ChunkStatus.UPLOADED)
                .active(true)
                .uploadOrder(order)
                .build();
        floor.addScanChunk(chunk);
        return scanChunkRepository.save(chunk);
    }
}
