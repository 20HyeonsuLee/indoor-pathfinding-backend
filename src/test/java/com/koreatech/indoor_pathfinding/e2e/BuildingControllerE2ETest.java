package com.koreatech.indoor_pathfinding.e2e;

import com.koreatech.indoor_pathfinding.modules.building.application.dto.request.BuildingCreateRequest;
import com.koreatech.indoor_pathfinding.modules.building.application.dto.request.BuildingUpdateRequest;
import com.koreatech.indoor_pathfinding.modules.building.domain.model.Building;
import com.koreatech.indoor_pathfinding.modules.building.domain.model.BuildingStatus;
import com.koreatech.indoor_pathfinding.modules.building.domain.repository.BuildingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BuildingControllerE2ETest extends BaseE2ETest {

    @Autowired
    private BuildingRepository buildingRepository;

    @AfterEach
    void tearDown() {
        buildingRepository.deleteAll();
    }

    @Nested
    @DisplayName("POST /api/v1/buildings")
    class CreateBuilding {

        @Test
        @DisplayName("should create building with valid request")
        void createBuilding_WithValidRequest_ReturnsCreated() throws Exception {
            BuildingCreateRequest request = new BuildingCreateRequest(
                    "Test Building",
                    "Test Description",
                    37.5665,
                    126.9780
            );

            mockMvc.perform(post("/api/v1/buildings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andDo(print())
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").exists())
                    .andExpect(jsonPath("$.name").value("Test Building"))
                    .andExpect(jsonPath("$.description").value("Test Description"))
                    .andExpect(jsonPath("$.latitude").value(37.5665))
                    .andExpect(jsonPath("$.longitude").value(126.9780))
                    .andExpect(jsonPath("$.status").value("DRAFT"));
        }

        @Test
        @DisplayName("should return 400 when name is blank")
        void createBuilding_WithBlankName_ReturnsBadRequest() throws Exception {
            BuildingCreateRequest request = new BuildingCreateRequest(
                    "",
                    "Test Description",
                    37.5665,
                    126.9780
            );

            mockMvc.perform(post("/api/v1/buildings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andDo(print())
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should create building without location")
        void createBuilding_WithoutLocation_ReturnsCreated() throws Exception {
            BuildingCreateRequest request = new BuildingCreateRequest(
                    "Building Without Location",
                    "Description",
                    null,
                    null
            );

            mockMvc.perform(post("/api/v1/buildings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andDo(print())
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("Building Without Location"))
                    .andExpect(jsonPath("$.latitude").doesNotExist())
                    .andExpect(jsonPath("$.longitude").doesNotExist());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/buildings")
    class GetAllBuildings {

        @Test
        @DisplayName("should return empty list when no buildings exist")
        void getAllBuildings_WhenEmpty_ReturnsEmptyList() throws Exception {
            mockMvc.perform(get("/api/v1/buildings"))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$").isEmpty());
        }

        @Test
        @DisplayName("should return all buildings")
        void getAllBuildings_WhenBuildingsExist_ReturnsAll() throws Exception {
            createTestBuilding("Building 1", BuildingStatus.DRAFT);
            createTestBuilding("Building 2", BuildingStatus.ACTIVE);

            mockMvc.perform(get("/api/v1/buildings"))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$", hasSize(2)));
        }

        @Test
        @DisplayName("should filter buildings by status")
        void getAllBuildings_WithStatusFilter_ReturnsFiltered() throws Exception {
            createTestBuilding("Draft Building", BuildingStatus.DRAFT);
            createTestBuilding("Active Building", BuildingStatus.ACTIVE);

            mockMvc.perform(get("/api/v1/buildings")
                            .param("status", "ACTIVE"))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].name").value("Active Building"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/buildings/{id}")
    class GetBuilding {

        @Test
        @DisplayName("should return building details")
        void getBuilding_WhenExists_ReturnsDetail() throws Exception {
            Building building = createTestBuilding("Test Building", BuildingStatus.DRAFT);

            mockMvc.perform(get("/api/v1/buildings/{id}", building.getId()))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(building.getId().toString()))
                    .andExpect(jsonPath("$.name").value("Test Building"))
                    .andExpect(jsonPath("$.floors").isArray())
                    .andExpect(jsonPath("$.verticalPassages").isArray());
        }

        @Test
        @DisplayName("should return 404 when building not found")
        void getBuilding_WhenNotExists_ReturnsNotFound() throws Exception {
            UUID nonExistentId = UUID.randomUUID();

            mockMvc.perform(get("/api/v1/buildings/{id}", nonExistentId))
                    .andDo(print())
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/buildings/{id}")
    class UpdateBuilding {

        @Test
        @DisplayName("should update building")
        void updateBuilding_WithValidRequest_ReturnsUpdated() throws Exception {
            Building building = createTestBuilding("Original Name", BuildingStatus.DRAFT);
            BuildingUpdateRequest request = new BuildingUpdateRequest(
                    "Updated Name",
                    "Updated Description",
                    35.1234,
                    129.5678
            );

            mockMvc.perform(put("/api/v1/buildings/{id}", building.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Updated Name"))
                    .andExpect(jsonPath("$.description").value("Updated Description"));
        }

        @Test
        @DisplayName("should return 404 when building not found")
        void updateBuilding_WhenNotExists_ReturnsNotFound() throws Exception {
            UUID nonExistentId = UUID.randomUUID();
            BuildingUpdateRequest request = new BuildingUpdateRequest(
                    "Updated Name",
                    "Updated Description",
                    null,
                    null
            );

            mockMvc.perform(put("/api/v1/buildings/{id}", nonExistentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andDo(print())
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/buildings/{id}")
    class DeleteBuilding {

        @Test
        @DisplayName("should delete building")
        void deleteBuilding_WhenExists_ReturnsNoContent() throws Exception {
            Building building = createTestBuilding("Test Building", BuildingStatus.DRAFT);

            mockMvc.perform(delete("/api/v1/buildings/{id}", building.getId()))
                    .andDo(print())
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/v1/buildings/{id}", building.getId()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 404 when building not found")
        void deleteBuilding_WhenNotExists_ReturnsNotFound() throws Exception {
            UUID nonExistentId = UUID.randomUUID();

            mockMvc.perform(delete("/api/v1/buildings/{id}", nonExistentId))
                    .andDo(print())
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("PATCH /api/v1/buildings/{id}/status")
    class UpdateBuildingStatus {

        @Test
        @DisplayName("should update building status")
        void updateBuildingStatus_WithValidStatus_ReturnsUpdated() throws Exception {
            Building building = createTestBuilding("Test Building", BuildingStatus.DRAFT);

            mockMvc.perform(patch("/api/v1/buildings/{id}/status", building.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("status", "ACTIVE"))))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
        }
    }

    private Building createTestBuilding(String name, BuildingStatus status) {
        Building building = Building.builder()
                .name(name)
                .description("Test Description")
                .status(status)
                .build();
        return buildingRepository.save(building);
    }
}
