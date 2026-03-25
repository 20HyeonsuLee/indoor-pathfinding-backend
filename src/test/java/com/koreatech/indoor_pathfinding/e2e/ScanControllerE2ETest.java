package com.koreatech.indoor_pathfinding.e2e;

import com.koreatech.indoor_pathfinding.modules.building.domain.model.Building;
import com.koreatech.indoor_pathfinding.modules.building.domain.model.BuildingStatus;
import com.koreatech.indoor_pathfinding.modules.building.domain.repository.BuildingRepository;
import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;
import com.koreatech.indoor_pathfinding.modules.floor.domain.repository.FloorRepository;
import com.koreatech.indoor_pathfinding.modules.scan.application.dto.request.MergeRequest;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ChunkStatus;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ScanChunk;
import com.koreatech.indoor_pathfinding.modules.scan.domain.repository.MergedScanRepository;
import com.koreatech.indoor_pathfinding.modules.scan.domain.repository.ScanChunkRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
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

    @Autowired
    private MergedScanRepository mergedScanRepository;

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
        mergedScanRepository.deleteAll();
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
            // given
            MockMultipartFile file = new MockMultipartFile(
                    "file", "test_scan.db", "application/octet-stream",
                    "test scan content".getBytes()
            );

            // when & then
            mockMvc.perform(multipart("/api/v1/floors/{floorId}/scans/chunks", testFloor.getId())
                            .file(file))
                    .andDo(print())
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").exists())
                    .andExpect(jsonPath("$.floorId").value(testFloor.getId().toString()))
                    .andExpect(jsonPath("$.fileName").value("test_scan.db"))
                    .andExpect(jsonPath("$.status").value("UPLOADED"))
                    .andExpect(jsonPath("$.active").value(true))
                    .andExpect(jsonPath("$.uploadOrder").value(1));
        }

        @Test
        @DisplayName("should auto-increment upload order")
        void uploadChunk_Multiple_IncreasesOrder() throws Exception {
            // given
            createTestChunk(testFloor, "scan1.db", 1);

            MockMultipartFile file = new MockMultipartFile(
                    "file", "scan2.db", "application/octet-stream",
                    "test scan content".getBytes()
            );

            // when & then
            mockMvc.perform(multipart("/api/v1/floors/{floorId}/scans/chunks", testFloor.getId())
                            .file(file))
                    .andDo(print())
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.uploadOrder").value(2));
        }

        @Test
        @DisplayName("should return 404 when floor not found")
        void uploadChunk_WhenFloorNotFound_ReturnsNotFound() throws Exception {
            // given
            MockMultipartFile file = new MockMultipartFile(
                    "file", "test_scan.db", "application/octet-stream",
                    "test scan content".getBytes()
            );

            // when & then
            mockMvc.perform(multipart("/api/v1/floors/{floorId}/scans/chunks", UUID.randomUUID())
                            .file(file))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 400 when file is not .db")
        void uploadChunk_WithInvalidExtension_ReturnsBadRequest() throws Exception {
            // given
            MockMultipartFile file = new MockMultipartFile(
                    "file", "test.txt", "text/plain",
                    "not a db file".getBytes()
            );

            // when & then
            mockMvc.perform(multipart("/api/v1/floors/{floorId}/scans/chunks", testFloor.getId())
                            .file(file))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/floors/{floorId}/scans/chunks")
    class GetChunks {

        @Test
        @DisplayName("should return empty list when no chunks")
        void getChunks_WhenEmpty_ReturnsEmptyList() throws Exception {
            mockMvc.perform(get("/api/v1/floors/{floorId}/scans/chunks", testFloor.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$").isEmpty());
        }

        @Test
        @DisplayName("should return all chunks for floor")
        void getChunks_WhenChunksExist_ReturnsAll() throws Exception {
            // given
            createTestChunk(testFloor, "scan1.db", 1);
            createTestChunk(testFloor, "scan2.db", 2);

            // when & then
            mockMvc.perform(get("/api/v1/floors/{floorId}/scans/chunks", testFloor.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].uploadOrder").value(1))
                    .andExpect(jsonPath("$[1].uploadOrder").value(2));
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/floors/{floorId}/scans/chunks/{chunkId}")
    class DeleteChunk {

        @Test
        @DisplayName("should delete chunk")
        void deleteChunk_WhenExists_ReturnsNoContent() throws Exception {
            // given
            ScanChunk chunk = createTestChunk(testFloor, "test_scan.db", 1);

            // when & then
            mockMvc.perform(delete("/api/v1/floors/{floorId}/scans/chunks/{chunkId}",
                            testFloor.getId(), chunk.getId()))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("should return 404 when chunk not found")
        void deleteChunk_WhenNotExists_ReturnsNotFound() throws Exception {
            mockMvc.perform(delete("/api/v1/floors/{floorId}/scans/chunks/{chunkId}",
                            testFloor.getId(), UUID.randomUUID()))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/floors/{floorId}/scans/merge")
    class Merge {

        @Test
        @DisplayName("should merge single chunk (skip merge, status=MERGED)")
        void merge_SingleChunk_SkipsMerge() throws Exception {
            // given
            ScanChunk chunk = createTestChunk(testFloor, "scan1.db", 1);
            MergeRequest request = new MergeRequest(List.of(chunk.getId()));

            // when & then
            mockMvc.perform(post("/api/v1/floors/{floorId}/scans/merge", testFloor.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("MERGED"))
                    .andExpect(jsonPath("$.floorId").value(testFloor.getId().toString()));
        }

        @Test
        @DisplayName("should initiate merge for multiple chunks (status=MERGING)")
        void merge_MultipleChunks_InitiatesMerge() throws Exception {
            // given
            ScanChunk chunk1 = createTestChunk(testFloor, "scan1.db", 1);
            ScanChunk chunk2 = createTestChunk(testFloor, "scan2.db", 2);
            MergeRequest request = new MergeRequest(List.of(chunk1.getId(), chunk2.getId()));

            // when & then
            mockMvc.perform(post("/api/v1/floors/{floorId}/scans/merge", testFloor.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("MERGING"));
        }

        @Test
        @DisplayName("should return 404 when chunk ID not found")
        void merge_WhenChunkNotFound_ReturnsNotFound() throws Exception {
            // given
            MergeRequest request = new MergeRequest(List.of(UUID.randomUUID()));

            // when & then
            mockMvc.perform(post("/api/v1/floors/{floorId}/scans/merge", testFloor.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 400 when chunk belongs to different floor")
        void merge_WhenChunkWrongFloor_ReturnsBadRequest() throws Exception {
            // given
            Floor otherFloor = Floor.builder().name("2층").level(2).build();
            testBuilding.addFloor(otherFloor);
            otherFloor = floorRepository.save(otherFloor);

            ScanChunk chunk = createTestChunk(otherFloor, "scan1.db", 1);
            MergeRequest request = new MergeRequest(List.of(chunk.getId()));

            // when & then
            mockMvc.perform(post("/api/v1/floors/{floorId}/scans/merge", testFloor.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should replace existing merged scan on re-merge")
        void merge_WhenAlreadyMerged_ReplacesExisting() throws Exception {
            // given - 먼저 1개로 병합
            ScanChunk chunk1 = createTestChunk(testFloor, "scan1.db", 1);
            MergeRequest firstRequest = new MergeRequest(List.of(chunk1.getId()));

            mockMvc.perform(post("/api/v1/floors/{floorId}/scans/merge", testFloor.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(firstRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("MERGED"));

            // when - 새 청크 추가 후 재병합
            ScanChunk chunk2 = createTestChunk(testFloor, "scan2.db", 2);
            MergeRequest secondRequest = new MergeRequest(List.of(chunk1.getId(), chunk2.getId()));

            // then - 기존 병합 교체
            mockMvc.perform(post("/api/v1/floors/{floorId}/scans/merge", testFloor.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(secondRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("MERGING"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/floors/{floorId}/scans/merge/status")
    class GetMergeStatus {

        @Test
        @DisplayName("should return merge status after merge")
        void getMergeStatus_WhenMerged_ReturnsStatus() throws Exception {
            // given
            ScanChunk chunk = createTestChunk(testFloor, "scan1.db", 1);
            MergeRequest request = new MergeRequest(List.of(chunk.getId()));

            mockMvc.perform(post("/api/v1/floors/{floorId}/scans/merge", testFloor.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)));

            // when & then
            mockMvc.perform(get("/api/v1/floors/{floorId}/scans/merge/status", testFloor.getId()))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("MERGED"));
        }

        @Test
        @DisplayName("should return 200 with empty body when no merged scan")
        void getMergeStatus_WhenNoMerge_ReturnsOkEmpty() throws Exception {
            mockMvc.perform(get("/api/v1/floors/{floorId}/scans/merge/status", testFloor.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").doesNotExist());
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
