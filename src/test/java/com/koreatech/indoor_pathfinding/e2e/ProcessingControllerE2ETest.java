package com.koreatech.indoor_pathfinding.e2e;

import com.koreatech.indoor_pathfinding.modules.building.domain.model.Building;
import com.koreatech.indoor_pathfinding.modules.building.domain.model.BuildingStatus;
import com.koreatech.indoor_pathfinding.modules.building.domain.repository.BuildingRepository;
import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;
import com.koreatech.indoor_pathfinding.modules.floor.domain.repository.FloorRepository;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.infrastructure.external.PathProcessingClient;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.MergedScan;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.MergedScanStatus;
import com.koreatech.indoor_pathfinding.modules.scan.domain.repository.MergedScanRepository;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProcessingControllerE2ETest extends BaseE2ETest {

    @Autowired
    private BuildingRepository buildingRepository;

    @Autowired
    private FloorRepository floorRepository;

    @Autowired
    private MergedScanRepository mergedScanRepository;

    @MockitoBean
    private PathProcessingClient pathProcessingClient;

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
        floorRepository.deleteAll();
        buildingRepository.deleteAll();
    }

    @Nested
    @DisplayName("POST /api/v1/floors/{floorId}/process")
    class StartProcessing {

        @Test
        @DisplayName("should start processing for valid merged scan")
        void startProcessing_WithValidMergedScan_ReturnsOk() throws Exception {
            MergedScan mergedScan = createTestMergedScan(testFloor, MergedScanStatus.MERGED);

            when(pathProcessingClient.uploadFile(any(Path.class))).thenReturn("file-123");
            when(pathProcessingClient.startProcessing("file-123")).thenReturn("job-456");

            mockMvc.perform(post("/api/v1/floors/{floorId}/process", testFloor.getId()))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.jobId").value("job-456"))
                    .andExpect(jsonPath("$.floorId").value(testFloor.getId().toString()));
        }

        @Test
        @DisplayName("should return 400 when merged scan is not processable")
        void startProcessing_WithInvalidState_ReturnsBadRequest() throws Exception {
            createTestMergedScan(testFloor, MergedScanStatus.EXTRACTING);

            mockMvc.perform(post("/api/v1/floors/{floorId}/process", testFloor.getId()))
                    .andDo(print())
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 404 when no merged scan exists")
        void startProcessing_WhenNoMergedScan_ReturnsNotFound() throws Exception {
            mockMvc.perform(post("/api/v1/floors/{floorId}/process", testFloor.getId()))
                    .andDo(print())
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/floors/{floorId}/preview/{jobId}/{imageType}")
    class GetPreviewImage {

        @Test
        @DisplayName("should return preview image")
        void getPreviewImage_WhenExists_ReturnsImage() throws Exception {
            byte[] testImage = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47};
            when(pathProcessingClient.getPreviewImage("job-123", "trajectory"))
                    .thenReturn(Mono.just(testImage));

            mockMvc.perform(get("/api/v1/floors/{floorId}/preview/{jobId}/{imageType}",
                            testFloor.getId(), "job-123", "trajectory"))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.IMAGE_PNG));
        }
    }

    private MergedScan createTestMergedScan(Floor floor, MergedScanStatus status) {
        MergedScan mergedScan = MergedScan.builder()
                .filePath("/test/path/merged.db")
                .status(status)
                .sourceChunkIds("[\"chunk-1\"]")
                .build();
        floor.updateMergedScan(mergedScan);
        return mergedScanRepository.save(mergedScan);
    }
}
