package com.koreatech.indoor_pathfinding.e2e;

import com.koreatech.indoor_pathfinding.modules.building.domain.model.Building;
import com.koreatech.indoor_pathfinding.modules.building.domain.model.BuildingStatus;
import com.koreatech.indoor_pathfinding.modules.building.domain.repository.BuildingRepository;
import com.koreatech.indoor_pathfinding.modules.floor.application.dto.request.FloorCreateRequest;
import com.koreatech.indoor_pathfinding.modules.floor.application.dto.request.FloorUpdateRequest;
import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;
import com.koreatech.indoor_pathfinding.modules.floor.domain.repository.FloorRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FloorControllerE2ETest extends BaseE2ETest {

    @Autowired
    private BuildingRepository buildingRepository;

    @Autowired
    private FloorRepository floorRepository;

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
        floorRepository.deleteAll();
        buildingRepository.deleteAll();
    }

    @Nested
    @DisplayName("POST /api/v1/buildings/{buildingId}/floors")
    class AddFloor {

        @Test
        @DisplayName("should add floor to building")
        void addFloor_WithValidRequest_ReturnsCreated() throws Exception {
            FloorCreateRequest request = new FloorCreateRequest("1st Floor", 1, 3.0);

            mockMvc.perform(post("/api/v1/buildings/{buildingId}/floors", testBuilding.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andDo(print())
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").exists())
                    .andExpect(jsonPath("$.name").value("1st Floor"))
                    .andExpect(jsonPath("$.level").value(1))
                    .andExpect(jsonPath("$.height").value(3.0));
        }

        @Test
        @DisplayName("should return 400 when name is blank")
        void addFloor_WithBlankName_ReturnsBadRequest() throws Exception {
            FloorCreateRequest request = new FloorCreateRequest("", 1, 3.0);

            mockMvc.perform(post("/api/v1/buildings/{buildingId}/floors", testBuilding.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andDo(print())
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 400 when level is null")
        void addFloor_WithNullLevel_ReturnsBadRequest() throws Exception {
            FloorCreateRequest request = new FloorCreateRequest("1st Floor", null, 3.0);

            mockMvc.perform(post("/api/v1/buildings/{buildingId}/floors", testBuilding.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andDo(print())
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 404 when building not found")
        void addFloor_WhenBuildingNotFound_ReturnsNotFound() throws Exception {
            FloorCreateRequest request = new FloorCreateRequest("1st Floor", 1, 3.0);

            mockMvc.perform(post("/api/v1/buildings/{buildingId}/floors", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andDo(print())
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/buildings/{buildingId}/floors")
    class GetFloors {

        @Test
        @DisplayName("should return empty list when no floors exist")
        void getFloors_WhenEmpty_ReturnsEmptyList() throws Exception {
            mockMvc.perform(get("/api/v1/buildings/{buildingId}/floors", testBuilding.getId()))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$").isEmpty());
        }

        @Test
        @DisplayName("should return all floors for building")
        void getFloors_WhenFloorsExist_ReturnsAll() throws Exception {
            createTestFloor(testBuilding, "1st Floor", 1);
            createTestFloor(testBuilding, "2nd Floor", 2);

            mockMvc.perform(get("/api/v1/buildings/{buildingId}/floors", testBuilding.getId()))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$", hasSize(2)));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/floors/{floorId}")
    class GetFloor {

        @Test
        @DisplayName("should return floor details")
        void getFloor_WhenExists_ReturnsDetail() throws Exception {
            Floor floor = createTestFloor(testBuilding, "1st Floor", 1);

            mockMvc.perform(get("/api/v1/floors/{floorId}", floor.getId()))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(floor.getId().toString()))
                    .andExpect(jsonPath("$.name").value("1st Floor"))
                    .andExpect(jsonPath("$.level").value(1));
        }

        @Test
        @DisplayName("should return 404 when floor not found")
        void getFloor_WhenNotExists_ReturnsNotFound() throws Exception {
            mockMvc.perform(get("/api/v1/floors/{floorId}", UUID.randomUUID()))
                    .andDo(print())
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/floors/{floorId}")
    class UpdateFloor {

        @Test
        @DisplayName("should update floor")
        void updateFloor_WithValidRequest_ReturnsUpdated() throws Exception {
            Floor floor = createTestFloor(testBuilding, "Original Name", 1);
            FloorUpdateRequest request = new FloorUpdateRequest("Updated Name", 4.0);

            mockMvc.perform(put("/api/v1/floors/{floorId}", floor.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Updated Name"))
                    .andExpect(jsonPath("$.height").value(4.0));
        }

        @Test
        @DisplayName("should return 404 when floor not found")
        void updateFloor_WhenNotExists_ReturnsNotFound() throws Exception {
            FloorUpdateRequest request = new FloorUpdateRequest("Updated Name", 4.0);

            mockMvc.perform(put("/api/v1/floors/{floorId}", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andDo(print())
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/floors/{floorId}")
    class DeleteFloor {

        @Test
        @DisplayName("should delete floor")
        void deleteFloor_WhenExists_ReturnsNoContent() throws Exception {
            Floor floor = createTestFloor(testBuilding, "Test Floor", 1);

            mockMvc.perform(delete("/api/v1/floors/{floorId}", floor.getId()))
                    .andDo(print())
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/v1/floors/{floorId}", floor.getId()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 404 when floor not found")
        void deleteFloor_WhenNotExists_ReturnsNotFound() throws Exception {
            mockMvc.perform(delete("/api/v1/floors/{floorId}", UUID.randomUUID()))
                    .andDo(print())
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/floors/{floorId}/path")
    class GetFloorPath {

        @Test
        @DisplayName("should return 404 when floor has no path")
        void getFloorPath_WhenNoPath_ReturnsNotFound() throws Exception {
            Floor floor = createTestFloor(testBuilding, "1st Floor", 1);

            mockMvc.perform(get("/api/v1/floors/{floorId}/path", floor.getId()))
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
}
