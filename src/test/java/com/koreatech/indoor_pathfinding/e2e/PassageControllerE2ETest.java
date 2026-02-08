package com.koreatech.indoor_pathfinding.e2e;

import com.koreatech.indoor_pathfinding.modules.building.domain.model.Building;
import com.koreatech.indoor_pathfinding.modules.building.domain.model.BuildingStatus;
import com.koreatech.indoor_pathfinding.modules.building.domain.repository.BuildingRepository;
import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;
import com.koreatech.indoor_pathfinding.modules.floor.domain.repository.FloorRepository;
import com.koreatech.indoor_pathfinding.modules.passage.domain.model.PassageType;
import com.koreatech.indoor_pathfinding.modules.passage.domain.model.VerticalPassage;
import com.koreatech.indoor_pathfinding.modules.passage.domain.repository.VerticalPassageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PassageControllerE2ETest extends BaseE2ETest {

    @Autowired
    private BuildingRepository buildingRepository;

    @Autowired
    private FloorRepository floorRepository;

    @Autowired
    private VerticalPassageRepository verticalPassageRepository;

    private Building testBuilding;
    private Floor floor1;
    private Floor floor2;

    @BeforeEach
    void setUp() {
        testBuilding = buildingRepository.save(
                Building.builder()
                        .name("Test Building")
                        .description("Test Description")
                        .status(BuildingStatus.DRAFT)
                        .build()
        );

        floor1 = createTestFloor(testBuilding, "1st Floor", 1);
        floor2 = createTestFloor(testBuilding, "2nd Floor", 2);
    }

    @AfterEach
    void tearDown() {
        verticalPassageRepository.deleteAll();
        floorRepository.deleteAll();
        buildingRepository.deleteAll();
    }

    @Nested
    @DisplayName("GET /api/v1/buildings/{buildingId}/passages")
    class GetVerticalPassages {

        @Test
        @DisplayName("should return empty list when no passages exist")
        void getVerticalPassages_WhenEmpty_ReturnsEmptyList() throws Exception {
            mockMvc.perform(get("/api/v1/buildings/{buildingId}/passages", testBuilding.getId()))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$").isEmpty());
        }

        @Test
        @DisplayName("should return all passages for building")
        void getVerticalPassages_WhenPassagesExist_ReturnsAll() throws Exception {
            createTestPassage(testBuilding, floor1, floor2, PassageType.STAIRCASE);
            createTestPassage(testBuilding, floor1, floor2, PassageType.ELEVATOR);

            mockMvc.perform(get("/api/v1/buildings/{buildingId}/passages", testBuilding.getId()))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$", hasSize(2)));
        }

        @Test
        @DisplayName("should filter passages by type")
        void getVerticalPassages_WithTypeFilter_ReturnsFiltered() throws Exception {
            createTestPassage(testBuilding, floor1, floor2, PassageType.STAIRCASE);
            createTestPassage(testBuilding, floor1, floor2, PassageType.ELEVATOR);

            mockMvc.perform(get("/api/v1/buildings/{buildingId}/passages", testBuilding.getId())
                            .param("type", "ELEVATOR"))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].type").value("ELEVATOR"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/passages/{passageId}")
    class GetVerticalPassageDetail {

        @Test
        @DisplayName("should return passage details")
        void getVerticalPassageDetail_WhenExists_ReturnsDetail() throws Exception {
            VerticalPassage passage = createTestPassage(testBuilding, floor1, floor2, PassageType.STAIRCASE);

            mockMvc.perform(get("/api/v1/passages/{passageId}", passage.getId()))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(passage.getId().toString()))
                    .andExpect(jsonPath("$.type").value("STAIRCASE"))
                    .andExpect(jsonPath("$.fromFloorLevel").value(1))
                    .andExpect(jsonPath("$.toFloorLevel").value(2));
        }

        @Test
        @DisplayName("should return 404 when passage not found")
        void getVerticalPassageDetail_WhenNotExists_ReturnsNotFound() throws Exception {
            mockMvc.perform(get("/api/v1/passages/{passageId}", UUID.randomUUID()))
                    .andDo(print())
                    .andExpect(status().isNotFound());
        }
    }

    private Floor createTestFloor(Building building, String name, int level) {
        Floor floor = Floor.builder()
                .name(name)
                .level(level)
                .height(3.0)
                .build();
        building.addFloor(floor);
        return floorRepository.save(floor);
    }

    private VerticalPassage createTestPassage(Building building, Floor fromFloor, Floor toFloor, PassageType type) {
        VerticalPassage passage = VerticalPassage.builder()
                .type(type)
                .fromFloor(fromFloor)
                .toFloor(toFloor)
                .entryX(0.0)
                .entryY(0.0)
                .entryZ(0.0)
                .exitX(0.0)
                .exitY(0.0)
                .exitZ(3.0)
                .build();
        building.addVerticalPassage(passage);
        return verticalPassageRepository.save(passage);
    }
}
