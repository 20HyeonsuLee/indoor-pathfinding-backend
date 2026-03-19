"""
포인트클라우드 기반 그래프 생성 모듈

pointcloud_pipeline의 함수들을 사용하여 그래프를 생성하고,
기존 path_service의 노드/엣지 포맷으로 변환한다.

[기존 pose 기반과의 차이]
- 포인트클라우드 → 2D 점유격자 → 스켈레톤 → 그래프
- 복도 중심선을 자동으로 찾아 노드를 배치
- 벽 구조를 반영한 정확한 경로 생성
"""
import sys
import os
import uuid
import numpy as np

# pointcloud_pipeline 모듈 경로 추가
_rtab_root = os.path.join(os.path.dirname(__file__), '..', '..')
sys.path.insert(0, _rtab_root)

from pointcloud_pipeline.run import (
    extract_pointcloud,
    separate_floors_by_z,
    create_occupancy_grid,
    extract_walkable_area,
    skeletonize,
    remove_spurs,
    skeleton_to_graph,
    detect_hubs,
    find_corridor_entries,
    create_hub_edges,
    CELL_SIZE,
)


def build_pointcloud_graph(db_path):
    """
    RTAB-Map DB에서 포인트클라우드를 추출하고
    2D 점유격자 기반 그래프를 생성한다.

    Returns:
        floor_graphs: {층번호: (nodes_dict, edges_dict)}
        floors_data: 시각화용 층별 데이터
        peaks: 층별 Z 피크
        world_points: 전체 포인트클라우드
        cam_positions: 카메라 궤적
    """
    # Step 1: 포인트클라우드 추출
    world_points, cam_positions = extract_pointcloud(db_path)

    # Step 2: 층 분리
    floors, peaks = separate_floors_by_z(world_points, cam_positions)

    # Step 3~6: 층별 처리
    floor_graphs = {}
    node_id_offset = 0

    for fi, fdata in sorted(floors.items()):
        # 점유 격자
        grid, x_min, y_min, w, h = create_occupancy_grid(fdata, CELL_SIZE)

        # 통행 가능 영역
        walkable = extract_walkable_area(grid)

        # 스켈레톤화 + spur 제거
        skel = skeletonize(walkable)
        skel = remove_spurs(skel, CELL_SIZE)

        # 그래프 추출
        nodes, edges = skeleton_to_graph(skel, x_min, y_min, CELL_SIZE, peaks[fi])

        # ID 오프셋 적용
        for n in nodes:
            n['id'] += node_id_offset
        for e in edges:
            e['from'] += node_id_offset
            e['to'] += node_id_offset

        # 허브 감지
        hubs, dist_map = detect_hubs(walkable, CELL_SIZE, grid=grid)
        if hubs:
            entries_per_hub = [
                find_corridor_entries(hub, walkable, skel, CELL_SIZE)
                for hub in hubs
            ]
            hub_nodes, hub_edges, _ = create_hub_edges(
                hubs, entries_per_hub, nodes, edges,
                x_min, y_min, CELL_SIZE, peaks[fi]
            )
            nodes.extend(hub_nodes)
            edges.extend(hub_edges)

        # 시각화 데이터 저장
        fdata['grid'] = grid
        fdata['walkable'] = walkable
        fdata['skeleton'] = skel
        fdata['nodes'] = nodes
        fdata['edges'] = edges

        # 기존 포맷으로 변환
        floor_level = fi + 1
        nodes_dict = _convert_nodes(nodes, floor_level)
        edges_dict = _convert_edges(edges, nodes_dict)

        floor_graphs[floor_level] = (nodes_dict, edges_dict)
        node_id_offset += len(nodes)

    return floor_graphs, floors, peaks, world_points, cam_positions


def _convert_nodes(pc_nodes, floor_level):
    """pointcloud_pipeline 노드를 path_service 포맷으로 변환"""
    id_map = {}
    result = []

    for n in pc_nodes:
        node_id = str(uuid.uuid4())
        id_map[n['id']] = node_id

        node_type = n.get('type', 'WAYPOINT')
        if node_type == 'HUB_ENTRY':
            node_type = 'JUNCTION'

        result.append({
            'id': node_id,
            'x': n['x'],
            'y': n['y'],
            'z': n['z'],
            'type': node_type,
            'original_index': n['id'],
            'floor_level': floor_level,
            'metadata': {},
            '_pc_id': n['id'],
        })

    return result


def _convert_edges(pc_edges, nodes_dict):
    """pointcloud_pipeline 엣지를 path_service 포맷으로 변환"""
    # pc_id → uuid 매핑
    id_map = {n['_pc_id']: n['id'] for n in nodes_dict}

    result = []
    for e in pc_edges:
        from_id = id_map.get(e['from'])
        to_id = id_map.get(e['to'])
        if not from_id or not to_id:
            continue

        edge_type = 'HORIZONTAL'
        if e.get('type') == 'HUB':
            edge_type = 'HORIZONTAL'

        result.append({
            'id': str(uuid.uuid4()),
            'from_node_id': from_id,
            'to_node_id': to_id,
            'distance': e['distance'],
            'is_bidirectional': True,
            'edge_type': edge_type,
            'metadata': {},
        })

    return result
