"""
=============================================================================
갈림길 감지 및 그래프 추출 모듈 (Junction Detection & Graph Extraction Module)
=============================================================================

이 모듈은 카메라 이동 경로에서 갈림길(교차점)을 감지하고,
길찾기에 사용할 노드(PathNode)와 엣지(PathEdge)를 추출합니다.

[갈림길(Junction)이란?]
복도가 갈라지거나 만나는 지점입니다.
    ┌─────────────┐
    │             │
    │    복도A    │
    │      │      │
    │      ▼      │
    │   ┌──┼──┐   │
    │   │  J  │   │  ← J: 갈림길 (Junction)
    │   │     │   │
    │ ←─┘     └─→ │
    │  복도B  복도C │
    └─────────────┘

[주요 기능]
1. detect_junctions: 경로에서 갈림길 위치 감지
2. extract_path_nodes: 경로에서 주요 노드 추출
3. extract_path_edges: 노드 간 연결 엣지 추출
4. build_path_graph: 전체 그래프 구축

[노드 타입]
- WAYPOINT: 일반 경로 포인트
- JUNCTION: 갈림길 (분기점)
- ENDPOINT: 경로의 시작/끝점
- POI_CANDIDATE: POI 후보 위치 (막다른 길 끝)

[사용 예시]
    from services.junction_detection import build_path_graph

    # 전체 그래프 구축
    nodes, edges = build_path_graph(smoothed_positions)

    # Spring 백엔드로 전송할 형식으로 변환
    for node in nodes:
        print(f"노드 {node['id']}: {node['type']} at ({node['x']}, {node['y']}, {node['z']})")

    for edge in edges:
        print(f"엣지: {edge['from_node']} → {edge['to_node']}, 거리: {edge['distance']:.2f}m")
"""

import numpy as np
from typing import List, Dict, Tuple, Optional, Set
from dataclasses import dataclass
from enum import Enum
import uuid
from scipy.spatial import cKDTree


# =============================================================================
# 타입 정의
# =============================================================================

class NodeType(Enum):
    """경로 노드의 타입"""
    WAYPOINT = "WAYPOINT"           # 일반 경로 포인트
    JUNCTION = "JUNCTION"           # 갈림길 (교차점)
    ENDPOINT = "ENDPOINT"           # 경로의 끝점
    POI_CANDIDATE = "POI_CANDIDATE" # POI 후보 (막다른 길)
    PASSAGE_ENTRY = "PASSAGE_ENTRY" # 계단/엘리베이터 입구
    PASSAGE_EXIT = "PASSAGE_EXIT"   # 계단/엘리베이터 출구


@dataclass
class PathNode:
    """경로 그래프의 노드"""
    id: str
    x: float
    y: float
    z: float
    node_type: NodeType
    original_index: int  # 원본 positions 배열에서의 인덱스
    floor_level: Optional[int] = None
    metadata: Optional[Dict] = None

    def to_dict(self) -> Dict:
        return {
            'id': self.id,
            'x': self.x,
            'y': self.y,
            'z': self.z,
            'type': self.node_type.value,
            'original_index': self.original_index,
            'floor_level': self.floor_level,
            'metadata': self.metadata or {}
        }


@dataclass
class PathEdge:
    """경로 그래프의 엣지"""
    id: str
    from_node_id: str
    to_node_id: str
    distance: float
    is_bidirectional: bool = True
    edge_type: str = "HORIZONTAL"  # HORIZONTAL, VERTICAL_STAIRCASE, VERTICAL_ELEVATOR
    metadata: Optional[Dict] = None

    def to_dict(self) -> Dict:
        return {
            'id': self.id,
            'from_node_id': self.from_node_id,
            'to_node_id': self.to_node_id,
            'distance': self.distance,
            'is_bidirectional': self.is_bidirectional,
            'edge_type': self.edge_type,
            'metadata': self.metadata or {}
        }


# =============================================================================
# 상수 정의
# =============================================================================

# 갈림길 감지 파라미터
JUNCTION_ANGLE_THRESHOLD = 45.0      # 방향 변화가 이 각도 이상이면 갈림길 후보
JUNCTION_NEIGHBOR_RADIUS = 2.0       # 갈림길 검증용 이웃 탐색 반경 (미터)
MIN_BRANCH_LENGTH = 3                # 최소 분기 길이 (포인트 수)

# 노드 추출 파라미터
NODE_SPACING = 1.0                   # 일반 노드 간 최소 간격 (미터)
JUNCTION_MERGE_RADIUS = 1.5          # 가까운 갈림길 병합 반경 (미터)

# 그래프 구축 파라미터
EDGE_CONNECTION_RADIUS = 3.0         # 엣지 연결 최대 거리 (미터)


# =============================================================================
# 갈림길 감지
# =============================================================================

def detect_junctions(
    positions: np.ndarray,
    angle_threshold: float = JUNCTION_ANGLE_THRESHOLD,
    min_branch_points: int = MIN_BRANCH_LENGTH
) -> List[Dict]:
    """
    경로에서 갈림길(교차점)을 감지합니다.

    [감지 방법]
    1. 각 포인트에서 방향 변화 각도 계산
    2. 급격한 방향 변화 = 갈림길 후보
    3. 주변 경로 분석으로 실제 분기점인지 검증

    [갈림길 판정 기준]
    - 방향 변화가 angle_threshold 이상
    - 분기되는 경로가 min_branch_points 이상의 길이

    [시각화]
              ↗ (새 방향)
    ──────→ J
              ↘ (또 다른 방향)

    Args:
        positions: [N, 3] 좌표 배열
        angle_threshold: 갈림길 판정 각도 (도)
        min_branch_points: 최소 분기 길이

    Returns:
        갈림길 정보 딕셔너리 리스트
        - 'index': 원본 배열에서의 인덱스
        - 'position': (x, y, z) 좌표
        - 'angle_change': 방향 변화 각도
        - 'branch_directions': 분기 방향 벡터 리스트

    Examples:
        >>> # T자 교차로가 있는 경로
        >>> junctions = detect_junctions(positions)
        >>> print(f"감지된 갈림길 수: {len(junctions)}")
    """
    if len(positions) < 5:
        return []

    junctions = []

    # Step 1: 방향 벡터 계산
    directions = np.diff(positions, axis=0)  # [N-1, 3]

    # Step 2: 각 포인트에서 방향 변화 각도 계산
    for i in range(1, len(directions) - 1):
        # 이전 방향
        v1 = directions[i - 1]
        # 다음 방향
        v2 = directions[i]

        # 각도 계산
        angle = _angle_between_vectors(v1, v2)

        # 갈림길 후보 판정
        if angle >= angle_threshold:
            # 추가 검증: 분기점 전후에 충분한 경로가 있는지
            points_before = i
            points_after = len(positions) - i - 1

            if points_before >= min_branch_points and points_after >= min_branch_points:
                junctions.append({
                    'index': i,
                    'position': positions[i].tolist(),
                    'angle_change': float(angle),
                    'incoming_direction': (v1 / np.linalg.norm(v1)).tolist() if np.linalg.norm(v1) > 0 else [0, 0, 0],
                    'outgoing_direction': (v2 / np.linalg.norm(v2)).tolist() if np.linalg.norm(v2) > 0 else [0, 0, 0]
                })

    # Step 3: 가까운 갈림길 병합 (노이즈 제거)
    junctions = _merge_nearby_junctions(junctions, merge_radius=JUNCTION_MERGE_RADIUS)

    return junctions


def _angle_between_vectors(v1: np.ndarray, v2: np.ndarray) -> float:
    """
    두 벡터 사이의 각도를 계산합니다 (도 단위).

    Args:
        v1, v2: 3D 벡터

    Returns:
        각도 (0° ~ 180°)
    """
    len1 = np.linalg.norm(v1)
    len2 = np.linalg.norm(v2)

    if len1 < 1e-10 or len2 < 1e-10:
        return 0.0

    cos_angle = np.dot(v1, v2) / (len1 * len2)
    cos_angle = np.clip(cos_angle, -1, 1)

    return float(np.degrees(np.arccos(cos_angle)))


def _merge_nearby_junctions(
    junctions: List[Dict],
    merge_radius: float
) -> List[Dict]:
    """
    가까이 있는 갈림길을 하나로 병합합니다.

    [목적]
    노이즈나 작은 흔들림으로 인해 감지된 가짜 갈림길을 제거합니다.

    Args:
        junctions: 갈림길 리스트
        merge_radius: 병합 반경

    Returns:
        병합된 갈림길 리스트
    """
    if len(junctions) < 2:
        return junctions

    merged = []
    used = set()

    for i, j1 in enumerate(junctions):
        if i in used:
            continue

        # 현재 갈림길과 가까운 다른 갈림길 찾기
        group = [j1]
        used.add(i)

        pos1 = np.array(j1['position'])

        for k, j2 in enumerate(junctions):
            if k in used:
                continue

            pos2 = np.array(j2['position'])
            dist = np.linalg.norm(pos1 - pos2)

            if dist < merge_radius:
                group.append(j2)
                used.add(k)

        # 그룹의 중심을 대표 갈림길로
        if len(group) == 1:
            merged.append(group[0])
        else:
            positions = np.array([g['position'] for g in group])
            center = np.mean(positions, axis=0)
            max_angle = max(g['angle_change'] for g in group)

            merged.append({
                'index': group[0]['index'],  # 첫 번째 인덱스 사용
                'position': center.tolist(),
                'angle_change': max_angle,
                'merged_count': len(group)
            })

    return merged


# =============================================================================
# 노드 추출
# =============================================================================

def extract_path_nodes(
    positions: np.ndarray,
    junctions: Optional[List[Dict]] = None,
    node_spacing: float = NODE_SPACING,
    floor_level: Optional[int] = None
) -> List[PathNode]:
    """
    경로에서 그래프 노드를 추출합니다.

    [노드 추출 전략]
    1. 시작점, 끝점 → ENDPOINT
    2. 감지된 갈림길 → JUNCTION
    3. 나머지는 일정 간격으로 → WAYPOINT
    4. 막다른 길의 끝 → POI_CANDIDATE

    [간격 조절]
    - node_spacing이 작으면: 노드 많음, 정밀한 경로
    - node_spacing이 크면: 노드 적음, 단순화된 경로

    Args:
        positions: [N, 3] 좌표 배열
        junctions: 감지된 갈림길 리스트 (없으면 자동 감지)
        node_spacing: 노드 간 최소 간격 (미터)
        floor_level: 층 번호 (옵션)

    Returns:
        PathNode 리스트
    """
    if len(positions) < 2:
        return []

    # 갈림길 자동 감지 (제공되지 않은 경우)
    if junctions is None:
        junctions = detect_junctions(positions)

    nodes = []
    junction_indices = {j['index'] for j in junctions}

    # Step 1: 시작점 노드
    nodes.append(PathNode(
        id=str(uuid.uuid4()),
        x=float(positions[0, 0]),
        y=float(positions[0, 1]),
        z=float(positions[0, 2]),
        node_type=NodeType.ENDPOINT,
        original_index=0,
        floor_level=floor_level
    ))

    last_node_pos = positions[0]
    last_node_idx = 0

    # Step 2: 중간 노드들
    for i in range(1, len(positions) - 1):
        pos = positions[i]
        dist_from_last = np.linalg.norm(pos - last_node_pos)

        # 갈림길이면 무조건 노드 추가
        if i in junction_indices:
            nodes.append(PathNode(
                id=str(uuid.uuid4()),
                x=float(pos[0]),
                y=float(pos[1]),
                z=float(pos[2]),
                node_type=NodeType.JUNCTION,
                original_index=i,
                floor_level=floor_level
            ))
            last_node_pos = pos
            last_node_idx = i

        # 일정 간격 이상이면 일반 노드 추가
        elif dist_from_last >= node_spacing:
            nodes.append(PathNode(
                id=str(uuid.uuid4()),
                x=float(pos[0]),
                y=float(pos[1]),
                z=float(pos[2]),
                node_type=NodeType.WAYPOINT,
                original_index=i,
                floor_level=floor_level
            ))
            last_node_pos = pos
            last_node_idx = i

    # Step 3: 끝점 노드
    end_idx = len(positions) - 1
    nodes.append(PathNode(
        id=str(uuid.uuid4()),
        x=float(positions[end_idx, 0]),
        y=float(positions[end_idx, 1]),
        z=float(positions[end_idx, 2]),
        node_type=NodeType.ENDPOINT,
        original_index=end_idx,
        floor_level=floor_level
    ))

    return nodes


# =============================================================================
# 엣지 추출
# =============================================================================

def extract_path_edges(
    nodes: List[PathNode],
    positions: np.ndarray,
    max_distance: float = EDGE_CONNECTION_RADIUS
) -> List[PathEdge]:
    """
    노드 간의 연결 엣지를 추출합니다.

    [엣지 연결 전략]
    1. 원본 경로 순서를 따라 연속 노드 연결
    2. 필요시 추가 연결 (예: 갈림길에서 여러 방향)

    [거리 계산]
    두 노드 사이의 실제 경로 거리를 계산합니다.
    직선 거리가 아닌, 경유하는 포인트들을 따라 측정합니다.

    Args:
        nodes: PathNode 리스트
        positions: 원본 좌표 배열
        max_distance: 최대 연결 거리

    Returns:
        PathEdge 리스트
    """
    if len(nodes) < 2:
        return []

    edges = []

    # 노드를 original_index로 정렬
    sorted_nodes = sorted(nodes, key=lambda n: n.original_index)

    # Step 1: 연속 노드 간 엣지 생성
    for i in range(len(sorted_nodes) - 1):
        node1 = sorted_nodes[i]
        node2 = sorted_nodes[i + 1]

        # 두 노드 사이의 실제 경로 거리 계산
        start_idx = node1.original_index
        end_idx = node2.original_index

        path_distance = _calculate_path_distance(positions, start_idx, end_idx)

        # 최대 거리 체크 (너무 먼 노드는 연결하지 않음)
        if path_distance <= max_distance:
            edges.append(PathEdge(
                id=str(uuid.uuid4()),
                from_node_id=node1.id,
                to_node_id=node2.id,
                distance=path_distance,
                is_bidirectional=True,
                edge_type="HORIZONTAL"
            ))

    return edges


def _calculate_path_distance(
    positions: np.ndarray,
    start_idx: int,
    end_idx: int
) -> float:
    """
    경로를 따라 두 인덱스 사이의 실제 거리를 계산합니다.

    [직선 거리 vs 경로 거리]
    직선: A────────────B (유클리드 거리)
    경로: A───*───*───B (실제 이동 거리)

    Args:
        positions: 좌표 배열
        start_idx: 시작 인덱스
        end_idx: 끝 인덱스

    Returns:
        경로 거리 (미터)
    """
    if start_idx >= end_idx:
        return 0.0

    total_distance = 0.0

    for i in range(start_idx, end_idx):
        segment_distance = np.linalg.norm(positions[i + 1] - positions[i])
        total_distance += segment_distance

    return float(total_distance)


# =============================================================================
# 전체 그래프 구축
# =============================================================================

def build_path_graph(
    positions: np.ndarray,
    floor_level: Optional[int] = None,
    node_spacing: float = NODE_SPACING,
    angle_threshold: float = JUNCTION_ANGLE_THRESHOLD
) -> Tuple[List[Dict], List[Dict]]:
    """
    전체 경로 그래프를 구축합니다.

    [처리 순서]
    1. 갈림길 감지
    2. 노드 추출
    3. 엣지 생성

    [반환 형식]
    Spring 백엔드 API에서 사용하기 쉬운 딕셔너리 형식으로 반환합니다.

    Args:
        positions: [N, 3] 좌표 배열
        floor_level: 층 번호
        node_spacing: 노드 간격
        angle_threshold: 갈림길 감지 각도

    Returns:
        (노드 딕셔너리 리스트, 엣지 딕셔너리 리스트)

    Examples:
        >>> nodes, edges = build_path_graph(positions, floor_level=1)
        >>> print(f"노드 {len(nodes)}개, 엣지 {len(edges)}개")
    """
    # Step 1: 갈림길 감지
    junctions = detect_junctions(positions, angle_threshold=angle_threshold)

    # Step 2: 노드 추출
    nodes = extract_path_nodes(
        positions,
        junctions=junctions,
        node_spacing=node_spacing,
        floor_level=floor_level
    )

    # Step 3: 엣지 추출
    edges = extract_path_edges(nodes, positions)

    # 딕셔너리 형식으로 변환
    nodes_dict = [node.to_dict() for node in nodes]
    edges_dict = [edge.to_dict() for edge in edges]

    return nodes_dict, edges_dict


# =============================================================================
# 그래프 분석 유틸리티
# =============================================================================

def get_graph_stats(nodes: List[Dict], edges: List[Dict]) -> Dict:
    """
    그래프 통계 정보를 계산합니다.

    Args:
        nodes: 노드 딕셔너리 리스트
        edges: 엣지 딕셔너리 리스트

    Returns:
        통계 딕셔너리
    """
    # 노드 타입별 개수
    type_counts = {}
    for node in nodes:
        node_type = node.get('type', 'UNKNOWN')
        type_counts[node_type] = type_counts.get(node_type, 0) + 1

    # 총 경로 길이
    total_distance = sum(edge['distance'] for edge in edges)

    # 평균 엣지 길이
    avg_edge_length = total_distance / len(edges) if edges else 0

    return {
        'total_nodes': len(nodes),
        'total_edges': len(edges),
        'node_type_counts': type_counts,
        'total_path_distance': total_distance,
        'average_edge_length': avg_edge_length,
        'junction_count': type_counts.get('JUNCTION', 0),
        'endpoint_count': type_counts.get('ENDPOINT', 0)
    }


def find_dead_ends(nodes: List[Dict], edges: List[Dict]) -> List[Dict]:
    """
    막다른 길(dead end)을 찾습니다.

    [막다른 길 정의]
    - 연결된 엣지가 1개뿐인 노드 (입구만 있고 출구 없음)
    - ENDPOINT가 아닌 노드 중 연결이 1개인 경우

    [활용]
    막다른 길 끝은 보통 특정 장소(강의실, 사무실 등)이므로
    POI 후보로 활용할 수 있습니다.

    Args:
        nodes: 노드 리스트
        edges: 엣지 리스트

    Returns:
        막다른 길 노드 리스트
    """
    # 각 노드의 연결 수 계산
    connection_count = {node['id']: 0 for node in nodes}

    for edge in edges:
        connection_count[edge['from_node_id']] += 1
        if edge['is_bidirectional']:
            connection_count[edge['to_node_id']] += 1

    # 연결이 1개인 노드 찾기
    dead_ends = []
    for node in nodes:
        if connection_count[node['id']] == 1:
            dead_ends.append(node)

    return dead_ends


def merge_floor_graphs(
    floor_graphs: Dict[int, Tuple[List[Dict], List[Dict]]],
    vertical_passages: List[Dict]
) -> Tuple[List[Dict], List[Dict]]:
    """
    여러 층의 그래프를 수직 통로로 연결하여 병합합니다.

    [처리 흐름]
    1. 모든 층의 노드와 엣지 수집
    2. 수직 통로(계단/엘리베이터) 정보로 층 간 엣지 추가

    Args:
        floor_graphs: {층번호: (노드리스트, 엣지리스트)} 딕셔너리
        vertical_passages: 수직 통로 정보 리스트

    Returns:
        (전체 노드 리스트, 전체 엣지 리스트)
    """
    all_nodes = []
    all_edges = []

    # 모든 층의 데이터 수집
    for floor_level, (nodes, edges) in floor_graphs.items():
        all_nodes.extend(nodes)
        all_edges.extend(edges)

    # 수직 통로로 층 간 연결
    for passage in vertical_passages:
        from_floor = passage.get('from_floor')
        to_floor = passage.get('to_floor')
        passage_type = passage.get('type', 'STAIRCASE')

        # 각 층의 가장 가까운 노드 찾기
        entry_node = _find_nearest_node_to_position(
            all_nodes,
            passage.get('entry_point'),
            floor_level=from_floor
        )
        exit_node = _find_nearest_node_to_position(
            all_nodes,
            passage.get('exit_point'),
            floor_level=to_floor
        )

        if entry_node and exit_node:
            # 수직 엣지 추가
            edge_type = f"VERTICAL_{passage_type}"
            all_edges.append({
                'id': str(uuid.uuid4()),
                'from_node_id': entry_node['id'],
                'to_node_id': exit_node['id'],
                'distance': passage.get('z_displacement', 3.0),
                'is_bidirectional': True,
                'edge_type': edge_type
            })

    return all_nodes, all_edges


def _find_nearest_node_to_position(
    nodes: List[Dict],
    position: Dict,
    floor_level: Optional[int] = None
) -> Optional[Dict]:
    """
    주어진 위치에 가장 가까운 노드를 찾습니다.

    Args:
        nodes: 노드 리스트
        position: {'x': float, 'y': float, 'z': float} 딕셔너리
        floor_level: 특정 층으로 필터링 (옵션)

    Returns:
        가장 가까운 노드 딕셔너리, 없으면 None
    """
    if not position or not nodes:
        return None

    target = np.array([position['x'], position['y'], position['z']])
    min_dist = float('inf')
    nearest = None

    for node in nodes:
        # 층 필터
        if floor_level is not None and node.get('floor_level') != floor_level:
            continue

        node_pos = np.array([node['x'], node['y'], node['z']])
        dist = np.linalg.norm(target - node_pos)

        if dist < min_dist:
            min_dist = dist
            nearest = node

    return nearest
