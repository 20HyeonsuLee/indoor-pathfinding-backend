package com.koreatech.indoor_pathfinding.modules.pathfinding.application.service;

import com.koreatech.indoor_pathfinding.modules.building.domain.model.Building;
import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.*;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.repository.PathEdgeRepository;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.repository.PathNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AStarPathfinderTest {

    @Mock
    private PathNodeRepository pathNodeRepository;

    @Mock
    private PathEdgeRepository pathEdgeRepository;

    @InjectMocks
    private AStarPathfinder aStarPathfinder;

    private Building building;
    private Floor floor1;
    private Floor floor2;

    @BeforeEach
    void setUp() {
        building = Building.builder().name("테스트 건물").build();
        ReflectionTestUtils.setField(building, "id", UUID.randomUUID());

        floor1 = Floor.builder().building(building).name("1층").level(1).build();
        ReflectionTestUtils.setField(floor1, "id", UUID.randomUUID());

        floor2 = Floor.builder().building(building).name("2층").level(2).build();
        ReflectionTestUtils.setField(floor2, "id", UUID.randomUUID());
    }

    private PathNode createNode(Floor floor, double x, double y, double z) {
        PathNode node = PathNode.builder()
            .floor(floor).x(x).y(y).z(z)
            .type(NodeType.WAYPOINT).build();
        ReflectionTestUtils.setField(node, "id", UUID.randomUUID());
        return node;
    }

    private PathEdge createEdge(PathNode from, PathNode to, EdgeType type) {
        return PathEdge.builder()
            .fromNode(from).toNode(to)
            .distance(from.distanceTo(to))
            .edgeType(type).isBidirectional(true).build();
    }

    @Test
    @DisplayName("직선 경로: A → B → C 최단 경로를 찾는다")
    void findPath_straightLine() {
        PathNode a = createNode(floor1, 0, 0, 0);
        PathNode b = createNode(floor1, 5, 0, 0);
        PathNode c = createNode(floor1, 10, 0, 0);

        PathEdge ab = createEdge(a, b, EdgeType.HORIZONTAL);
        PathEdge bc = createEdge(b, c, EdgeType.HORIZONTAL);

        when(pathNodeRepository.findByBuildingId(any())).thenReturn(List.of(a, b, c));
        when(pathEdgeRepository.findByBuildingId(any())).thenReturn(List.of(ab, bc));

        List<PathNode> path = aStarPathfinder.findPath(a, c, PathPreference.SHORTEST);

        assertThat(path).hasSize(3);
        assertThat(path.get(0).getId()).isEqualTo(a.getId());
        assertThat(path.get(1).getId()).isEqualTo(b.getId());
        assertThat(path.get(2).getId()).isEqualTo(c.getId());
    }

    @Test
    @DisplayName("분기 경로: 짧은 경로를 선택한다")
    void findPath_choosesShortestBranch() {
        // A --(5)--> B --(5)--> D (총 10)
        // A --(3)--> C --(3)--> D (총 6) ← 이쪽이 짧음
        PathNode a = createNode(floor1, 0, 0, 0);
        PathNode b = createNode(floor1, 5, 0, 0);
        PathNode c = createNode(floor1, 0, 3, 0);
        PathNode d = createNode(floor1, 3, 3, 0);

        PathEdge ab = createEdge(a, b, EdgeType.HORIZONTAL);
        PathEdge bd = createEdge(b, d, EdgeType.HORIZONTAL);
        PathEdge ac = createEdge(a, c, EdgeType.HORIZONTAL);
        PathEdge cd = createEdge(c, d, EdgeType.HORIZONTAL);

        when(pathNodeRepository.findByBuildingId(any())).thenReturn(List.of(a, b, c, d));
        when(pathEdgeRepository.findByBuildingId(any())).thenReturn(List.of(ab, bd, ac, cd));

        List<PathNode> path = aStarPathfinder.findPath(a, d, PathPreference.SHORTEST);

        // A→C→D가 A→B→D보다 짧아야 함
        assertThat(path).hasSize(3);
        assertThat(path.get(0).getId()).isEqualTo(a.getId());
        assertThat(path.get(1).getId()).isEqualTo(c.getId());
        assertThat(path.get(2).getId()).isEqualTo(d.getId());
    }

    @Test
    @DisplayName("연결되지 않은 노드로는 빈 경로를 반환한다")
    void findPath_disconnectedNodes_returnsEmpty() {
        PathNode a = createNode(floor1, 0, 0, 0);
        PathNode b = createNode(floor1, 5, 0, 0);
        PathNode isolated = createNode(floor1, 100, 100, 0);

        PathEdge ab = createEdge(a, b, EdgeType.HORIZONTAL);

        when(pathNodeRepository.findByBuildingId(any())).thenReturn(List.of(a, b, isolated));
        when(pathEdgeRepository.findByBuildingId(any())).thenReturn(List.of(ab));

        List<PathNode> path = aStarPathfinder.findPath(a, isolated, PathPreference.SHORTEST);

        assertThat(path).isEmpty();
    }

    @Test
    @DisplayName("출발지와 목적지가 같으면 단일 노드 경로를 반환한다")
    void findPath_sameStartAndGoal() {
        PathNode a = createNode(floor1, 0, 0, 0);

        when(pathNodeRepository.findByBuildingId(any())).thenReturn(List.of(a));
        when(pathEdgeRepository.findByBuildingId(any())).thenReturn(List.of());

        List<PathNode> path = aStarPathfinder.findPath(a, a, PathPreference.SHORTEST);

        assertThat(path).hasSize(1);
        assertThat(path.get(0).getId()).isEqualTo(a.getId());
    }

    @Test
    @DisplayName("ELEVATOR_FIRST 선호 시 엘리베이터 경로를 선택한다")
    void findPath_elevatorFirst_prefersElevator() {
        // A → stairEntry → stairExit → D (계단 경로)
        // A → elevEntry → elevExit → D (엘리베이터 경로)
        // 거리는 같지만 ELEVATOR_FIRST면 엘리베이터 가중치가 낮아 엘리베이터를 선택
        PathNode a = createNode(floor1, 0, 0, 0);
        PathNode stairEntry = createNode(floor1, 10, 0, 0);
        PathNode stairExit = createNode(floor2, 10, 0, 3);
        PathNode elevEntry = createNode(floor1, 0, 10, 0);
        PathNode elevExit = createNode(floor2, 0, 10, 3);
        PathNode d = createNode(floor2, 5, 5, 3);

        PathEdge aStair = createEdge(a, stairEntry, EdgeType.HORIZONTAL);
        PathEdge stairVert = createEdge(stairEntry, stairExit, EdgeType.VERTICAL_STAIRCASE);
        PathEdge stairD = createEdge(stairExit, d, EdgeType.HORIZONTAL);

        PathEdge aElev = createEdge(a, elevEntry, EdgeType.HORIZONTAL);
        PathEdge elevVert = createEdge(elevEntry, elevExit, EdgeType.VERTICAL_ELEVATOR);
        PathEdge elevD = createEdge(elevExit, d, EdgeType.HORIZONTAL);

        when(pathNodeRepository.findByBuildingId(any()))
            .thenReturn(List.of(a, stairEntry, stairExit, elevEntry, elevExit, d));
        when(pathEdgeRepository.findByBuildingId(any()))
            .thenReturn(List.of(aStair, stairVert, stairD, aElev, elevVert, elevD));

        List<PathNode> path = aStarPathfinder.findPath(a, d, PathPreference.ELEVATOR_FIRST);

        // 엘리베이터 경로를 선택해야 함
        assertThat(path).contains(elevEntry);
        assertThat(path).doesNotContain(stairEntry);
    }

    @Test
    @DisplayName("단방향 엣지는 역방향 탐색이 불가하다")
    void findPath_unidirectionalEdge() {
        PathNode a = createNode(floor1, 0, 0, 0);
        PathNode b = createNode(floor1, 5, 0, 0);

        // A→B 단방향
        PathEdge ab = PathEdge.builder()
            .fromNode(a).toNode(b)
            .distance(a.distanceTo(b))
            .edgeType(EdgeType.HORIZONTAL)
            .isBidirectional(false).build();

        when(pathNodeRepository.findByBuildingId(any())).thenReturn(List.of(a, b));
        when(pathEdgeRepository.findByBuildingId(any())).thenReturn(List.of(ab));

        // B→A 방향은 불가
        List<PathNode> path = aStarPathfinder.findPath(b, a, PathPreference.SHORTEST);
        assertThat(path).isEmpty();

        // A→B 방향은 가능
        List<PathNode> forward = aStarPathfinder.findPath(a, b, PathPreference.SHORTEST);
        assertThat(forward).hasSize(2);
    }
}
