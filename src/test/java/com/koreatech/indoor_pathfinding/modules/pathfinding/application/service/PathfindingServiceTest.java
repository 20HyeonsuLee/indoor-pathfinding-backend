package com.koreatech.indoor_pathfinding.modules.pathfinding.application.service;

import com.koreatech.indoor_pathfinding.modules.building.domain.model.Building;
import com.koreatech.indoor_pathfinding.modules.building.domain.repository.BuildingRepository;
import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;
import com.koreatech.indoor_pathfinding.modules.floor.domain.repository.FloorRepository;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.request.PathfindingRequest;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.response.PathfindingResponse;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.query.PoiReader;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.NodeType;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.PathNode;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.PathPreference;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.repository.PathNodeRepository;
import com.koreatech.indoor_pathfinding.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PathfindingServiceTest {

    @Mock
    private PathNodeRepository pathNodeRepository;
    @Mock
    private FloorRepository floorRepository;
    @Mock
    private BuildingRepository buildingRepository;
    @Mock
    private PoiReader poiReader;
    @Mock
    private AStarPathfinder aStarPathfinder;

    @InjectMocks
    private PathfindingService pathfindingService;

    private UUID buildingId;
    private Building building;
    private Floor floor1;

    @BeforeEach
    void setUp() {
        buildingId = UUID.randomUUID();
        building = Building.builder().name("테스트 건물").build();
        ReflectionTestUtils.setField(building, "id", buildingId);

        floor1 = Floor.builder().building(building).name("1층").level(1).build();
        ReflectionTestUtils.setField(floor1, "id", UUID.randomUUID());
    }

    private PathNode createNode(Floor floor, double x, double y, double z, String poiName) {
        PathNode node = PathNode.builder()
            .floor(floor).x(x).y(y).z(z)
            .type(poiName != null ? NodeType.POI : NodeType.WAYPOINT)
            .poiName(poiName).build();
        ReflectionTestUtils.setField(node, "id", UUID.randomUUID());
        return node;
    }

    @Test
    @DisplayName("사용자 위치가 노드에서 멀면 보정 스텝이 추가된다")
    void findPath_addsSnapCorrectionStep_whenUserFarFromNode() {
        // 사용자 위치: (100, 100), 가장 가까운 노드: (105, 100) → 5m 차이
        PathNode startNode = createNode(floor1, 105, 100, 0, null);
        PathNode goalNode = createNode(floor1, 200, 100, 0, "목적지");

        when(buildingRepository.existsById(buildingId)).thenReturn(true);
        when(pathNodeRepository.findByBuildingId(buildingId)).thenReturn(List.of(startNode, goalNode));
        when(floorRepository.findByBuildingIdAndLevel(buildingId, 1)).thenReturn(Optional.of(floor1));
        when(pathNodeRepository.findNearestNodeOnFloor(any(), eq(100.0), eq(100.0), eq(0.0)))
            .thenReturn(Optional.of(startNode));
        when(poiReader.findNodeByPoiName(buildingId, "목적지")).thenReturn(goalNode);
        when(aStarPathfinder.findPath(startNode, goalNode, PathPreference.SHORTEST))
            .thenReturn(List.of(startNode, goalNode));

        PathfindingRequest request = new PathfindingRequest(1, 100.0, 100.0, 0.0, "목적지", PathPreference.SHORTEST);
        PathfindingResponse response = pathfindingService.findPath(buildingId, request);

        // 첫 스텝: "경로까지 5.0m 이동" (보정)
        assertThat(response.steps().get(0).instruction()).contains("경로까지");
        assertThat(response.steps().get(0).position().x()).isEqualTo(100.0); // 사용자 실제 위치

        // 두 번째 스텝: "출발" (첫 그래프 노드)
        assertThat(response.steps().get(1).instruction()).isEqualTo("출발");
        assertThat(response.steps().get(1).position().x()).isEqualTo(105.0); // 스냅된 노드 위치

        // 총 거리에 보정 거리 포함 (5m + 95m = 100m)
        assertThat(response.totalDistance()).isGreaterThan(99.0);
    }

    @Test
    @DisplayName("사용자 위치가 노드 위에 있으면 (10cm 미만) 보정 스텝이 없다")
    void findPath_noSnapStep_whenUserOnNode() {
        PathNode startNode = createNode(floor1, 0.05, 0.0, 0, null);
        PathNode goalNode = createNode(floor1, 10, 0, 0, "목적지");

        when(buildingRepository.existsById(buildingId)).thenReturn(true);
        when(pathNodeRepository.findByBuildingId(buildingId)).thenReturn(List.of(startNode, goalNode));
        when(floorRepository.findByBuildingIdAndLevel(buildingId, 1)).thenReturn(Optional.of(floor1));
        when(pathNodeRepository.findNearestNodeOnFloor(any(), eq(0.0), eq(0.0), eq(0.0)))
            .thenReturn(Optional.of(startNode));
        when(poiReader.findNodeByPoiName(buildingId, "목적지")).thenReturn(goalNode);
        when(aStarPathfinder.findPath(startNode, goalNode, PathPreference.SHORTEST))
            .thenReturn(List.of(startNode, goalNode));

        PathfindingRequest request = new PathfindingRequest(1, 0.0, 0.0, 0.0, "목적지", PathPreference.SHORTEST);
        PathfindingResponse response = pathfindingService.findPath(buildingId, request);

        // 첫 스텝이 바로 "출발" (보정 스텝 없음)
        assertThat(response.steps().get(0).instruction()).isEqualTo("출발");
    }

    @Test
    @DisplayName("출발지와 목적지가 같은 노드이면 즉시 도착 응답")
    void findPath_sameStartAndGoal_returnsImmediateArrival() {
        PathNode sameNode = createNode(floor1, 10, 10, 0, "여기");

        when(buildingRepository.existsById(buildingId)).thenReturn(true);
        when(pathNodeRepository.findByBuildingId(buildingId)).thenReturn(List.of(sameNode));
        when(floorRepository.findByBuildingIdAndLevel(buildingId, 1)).thenReturn(Optional.of(floor1));
        when(pathNodeRepository.findNearestNodeOnFloor(any(), eq(10.0), eq(10.0), eq(0.0)))
            .thenReturn(Optional.of(sameNode));
        when(poiReader.findNodeByPoiName(buildingId, "여기")).thenReturn(sameNode);

        PathfindingRequest request = new PathfindingRequest(1, 10.0, 10.0, 0.0, "여기", null);
        PathfindingResponse response = pathfindingService.findPath(buildingId, request);

        assertThat(response.totalDistance()).isEqualTo(0.0);
        assertThat(response.steps()).hasSize(1);
        assertThat(response.steps().get(0).instruction()).contains("도착");
        assertThat(response.floorTransitions()).isEmpty();
    }

    @Test
    @DisplayName("출발지와 목적지가 같지만 사용자가 멀리 있으면 보정 거리 포함")
    void findPath_sameNodeButUserFar_includesSnapDistance() {
        PathNode sameNode = createNode(floor1, 10, 10, 0, "여기");

        when(buildingRepository.existsById(buildingId)).thenReturn(true);
        when(pathNodeRepository.findByBuildingId(buildingId)).thenReturn(List.of(sameNode));
        when(floorRepository.findByBuildingIdAndLevel(buildingId, 1)).thenReturn(Optional.of(floor1));
        // 사용자가 (0, 0)에 있고 nearest node는 (10, 10) → ~14.14m
        when(pathNodeRepository.findNearestNodeOnFloor(any(), eq(0.0), eq(0.0), eq(0.0)))
            .thenReturn(Optional.of(sameNode));
        when(poiReader.findNodeByPoiName(buildingId, "여기")).thenReturn(sameNode);

        PathfindingRequest request = new PathfindingRequest(1, 0.0, 0.0, 0.0, "여기", null);
        PathfindingResponse response = pathfindingService.findPath(buildingId, request);

        // 보정 거리 포함 (~14.14m)
        assertThat(response.totalDistance()).isGreaterThan(14.0);
        assertThat(response.steps()).hasSize(2); // 보정 스텝 + 도착 스텝
        assertThat(response.steps().get(0).instruction()).contains("경로까지");
    }

    @Test
    @DisplayName("존재하지 않는 건물 ID로 요청하면 BUILDING_NOT_FOUND")
    void findPath_buildingNotFound() {
        when(buildingRepository.existsById(any())).thenReturn(false);

        PathfindingRequest request = new PathfindingRequest(1, 0.0, 0.0, null, "목적지", null);

        assertThatThrownBy(() -> pathfindingService.findPath(UUID.randomUUID(), request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Building not found");
    }

    @Test
    @DisplayName("그래프가 없는 건물로 요청하면 GRAPH_NOT_BUILT")
    void findPath_noGraph() {
        when(buildingRepository.existsById(buildingId)).thenReturn(true);
        when(pathNodeRepository.findByBuildingId(buildingId)).thenReturn(List.of());

        PathfindingRequest request = new PathfindingRequest(1, 0.0, 0.0, null, "목적지", null);

        assertThatThrownBy(() -> pathfindingService.findPath(buildingId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("No pathfinding graph");
    }

    @Test
    @DisplayName("존재하지 않는 층 번호로 요청하면 FLOOR_NOT_FOUND")
    void findPath_floorNotFound() {
        PathNode dummyNode = createNode(floor1, 0, 0, 0, null);

        when(buildingRepository.existsById(buildingId)).thenReturn(true);
        when(pathNodeRepository.findByBuildingId(buildingId)).thenReturn(List.of(dummyNode));
        when(floorRepository.findByBuildingIdAndLevel(buildingId, 99)).thenReturn(Optional.empty());

        PathfindingRequest request = new PathfindingRequest(99, 0.0, 0.0, null, "목적지", null);

        assertThatThrownBy(() -> pathfindingService.findPath(buildingId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Floor 99 not found");
    }

    @Test
    @DisplayName("경로를 찾을 수 없으면 NO_PATH_AVAILABLE")
    void findPath_noPathAvailable() {
        PathNode startNode = createNode(floor1, 0, 0, 0, null);
        PathNode goalNode = createNode(floor1, 100, 100, 0, "먼곳");

        when(buildingRepository.existsById(buildingId)).thenReturn(true);
        when(pathNodeRepository.findByBuildingId(buildingId)).thenReturn(List.of(startNode, goalNode));
        when(floorRepository.findByBuildingIdAndLevel(buildingId, 1)).thenReturn(Optional.of(floor1));
        when(pathNodeRepository.findNearestNodeOnFloor(any(), eq(0.0), eq(0.0), eq(0.0)))
            .thenReturn(Optional.of(startNode));
        when(poiReader.findNodeByPoiName(buildingId, "먼곳")).thenReturn(goalNode);
        when(aStarPathfinder.findPath(startNode, goalNode, PathPreference.SHORTEST))
            .thenReturn(List.of()); // 경로 없음

        PathfindingRequest request = new PathfindingRequest(1, 0.0, 0.0, 0.0, "먼곳", PathPreference.SHORTEST);

        assertThatThrownBy(() -> pathfindingService.findPath(buildingId, request))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("startZ가 null이면 기본값 0.0 사용")
    void findPath_nullStartZ_usesDefault() {
        PathNode startNode = createNode(floor1, 0, 0, 0, null);
        PathNode goalNode = createNode(floor1, 10, 0, 0, "목적지");

        when(buildingRepository.existsById(buildingId)).thenReturn(true);
        when(pathNodeRepository.findByBuildingId(buildingId)).thenReturn(List.of(startNode, goalNode));
        when(floorRepository.findByBuildingIdAndLevel(buildingId, 1)).thenReturn(Optional.of(floor1));
        when(pathNodeRepository.findNearestNodeOnFloor(any(), eq(0.0), eq(0.0), eq(0.0)))
            .thenReturn(Optional.of(startNode));
        when(poiReader.findNodeByPoiName(buildingId, "목적지")).thenReturn(goalNode);
        when(aStarPathfinder.findPath(startNode, goalNode, PathPreference.SHORTEST))
            .thenReturn(List.of(startNode, goalNode));

        // startZ = null
        PathfindingRequest request = new PathfindingRequest(1, 0.0, 0.0, null, "목적지", null);
        PathfindingResponse response = pathfindingService.findPath(buildingId, request);

        assertThat(response.steps()).isNotEmpty();
    }
}
