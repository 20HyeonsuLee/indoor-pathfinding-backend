package com.koreatech.indoor_pathfinding.modules.pathfinding.application.command;

import com.koreatech.indoor_pathfinding.modules.building.domain.model.Building;
import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;
import com.koreatech.indoor_pathfinding.modules.floor.domain.repository.FloorRepository;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.request.PoiCreateRequest;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.request.PoiRegisterRequest;
import com.koreatech.indoor_pathfinding.modules.pathfinding.application.dto.response.PoiResponse;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.NodeType;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.PathNode;
import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.PoiCategory;
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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PoiManagerTest {

    @Mock
    private PathNodeRepository pathNodeRepository;
    @Mock
    private FloorRepository floorRepository;

    @InjectMocks
    private PoiManager poiManager;

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

    private PathNode createNode(double x, double y, double z) {
        PathNode node = PathNode.builder()
            .floor(floor1).x(x).y(y).z(z)
            .type(NodeType.WAYPOINT).build();
        ReflectionTestUtils.setField(node, "id", UUID.randomUUID());
        return node;
    }

    @Test
    @DisplayName("좌표로 POI 생성 시 가장 가까운 기존 노드에 등록된다")
    void createPoi_snapsToNearestNode() {
        PathNode nearestNode = createNode(10.5, 20.3, 0);

        when(floorRepository.findByBuildingIdAndLevel(buildingId, 1)).thenReturn(Optional.of(floor1));
        when(pathNodeRepository.findByBuildingIdAndPoiName(buildingId, "강의실")).thenReturn(Optional.empty());
        when(pathNodeRepository.findNearestNodeOnFloor(any(), eq(10.0), eq(20.0), eq(0.0)))
            .thenReturn(Optional.of(nearestNode));
        when(pathNodeRepository.save(nearestNode)).thenReturn(nearestNode);

        PoiCreateRequest request = new PoiCreateRequest("강의실", PoiCategory.CLASSROOM, 1, 10.0, 20.0, null);
        PoiResponse response = poiManager.createPoi(buildingId, request);

        assertThat(response.nodeId()).isEqualTo(nearestNode.getId());
        assertThat(response.name()).isEqualTo("강의실");
        assertThat(response.x()).isEqualTo(10.5); // 노드 좌표 (사용자 좌표 아님)
    }

    @Test
    @DisplayName("같은 이름의 POI가 이미 존재하면 예외 발생")
    void createPoi_duplicateName_throwsException() {
        PathNode existing = createNode(5, 5, 0);
        existing.updatePoi("강의실", PoiCategory.CLASSROOM);

        when(pathNodeRepository.findByBuildingIdAndPoiName(buildingId, "강의실"))
            .thenReturn(Optional.of(existing));

        PoiCreateRequest request = new PoiCreateRequest("강의실", PoiCategory.CLASSROOM, 1, 10.0, 20.0, null);

        assertThatThrownBy(() -> poiManager.createPoi(buildingId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("존재하지 않는 층에 POI 생성하면 FLOOR_NOT_FOUND")
    void createPoi_invalidFloor_throwsException() {
        when(floorRepository.findByBuildingIdAndLevel(buildingId, 99)).thenReturn(Optional.empty());

        PoiCreateRequest request = new PoiCreateRequest("강의실", PoiCategory.CLASSROOM, 99, 10.0, 20.0, null);

        assertThatThrownBy(() -> poiManager.createPoi(buildingId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Floor 99 not found");
    }

    @Test
    @DisplayName("해당 층에 노드가 없으면 NODE_NOT_FOUND")
    void createPoi_noNodesOnFloor_throwsException() {
        when(floorRepository.findByBuildingIdAndLevel(buildingId, 1)).thenReturn(Optional.of(floor1));
        when(pathNodeRepository.findByBuildingIdAndPoiName(buildingId, "강의실")).thenReturn(Optional.empty());
        when(pathNodeRepository.findNearestNodeOnFloor(any(), anyDouble(), anyDouble(), anyDouble()))
            .thenReturn(Optional.empty());

        PoiCreateRequest request = new PoiCreateRequest("강의실", PoiCategory.CLASSROOM, 1, 10.0, 20.0, null);

        assertThatThrownBy(() -> poiManager.createPoi(buildingId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("No nodes available");
    }

    @Test
    @DisplayName("기존 노드 ID로 POI 등록 시 같은 이름 중복 검증")
    void registerPoi_duplicateName_throwsException() {
        PathNode targetNode = createNode(10, 10, 0);
        PathNode existingPoi = createNode(5, 5, 0);
        existingPoi.updatePoi("강의실", PoiCategory.CLASSROOM);

        when(pathNodeRepository.findById(targetNode.getId())).thenReturn(Optional.of(targetNode));
        when(pathNodeRepository.findByBuildingIdAndPoiName(buildingId, "강의실"))
            .thenReturn(Optional.of(existingPoi));

        PoiRegisterRequest request = new PoiRegisterRequest("강의실", PoiCategory.CLASSROOM);

        assertThatThrownBy(() -> poiManager.registerPoi(targetNode.getId(), request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("존재하지 않는 노드에 POI 등록하면 NODE_NOT_FOUND")
    void registerPoi_nodeNotFound() {
        UUID fakeId = UUID.randomUUID();
        when(pathNodeRepository.findById(fakeId)).thenReturn(Optional.empty());

        PoiRegisterRequest request = new PoiRegisterRequest("강의실", PoiCategory.CLASSROOM);

        assertThatThrownBy(() -> poiManager.registerPoi(fakeId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Node not found");
    }

    @Test
    @DisplayName("POI가 없는 노드에서 삭제하면 POI_NOT_FOUND")
    void deletePoi_noPoi_throwsException() {
        PathNode node = createNode(10, 10, 0); // poiName = null

        when(pathNodeRepository.findById(node.getId())).thenReturn(Optional.of(node));

        assertThatThrownBy(() -> poiManager.deletePoi(node.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("No POI registered");
    }

    @Test
    @DisplayName("POI 삭제 후 노드 타입이 WAYPOINT로 돌아간다")
    void deletePoi_resetsNodeType() {
        PathNode node = createNode(10, 10, 0);
        node.updatePoi("삭제할장소", PoiCategory.OTHER);
        assertThat(node.getType()).isEqualTo(NodeType.POI);

        when(pathNodeRepository.findById(node.getId())).thenReturn(Optional.of(node));
        when(pathNodeRepository.save(node)).thenReturn(node);

        poiManager.deletePoi(node.getId());

        assertThat(node.getType()).isEqualTo(NodeType.WAYPOINT);
        assertThat(node.getPoiName()).isNull();
    }
}
