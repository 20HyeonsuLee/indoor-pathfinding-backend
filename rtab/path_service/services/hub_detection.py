"""
=============================================================================
허브 탐지 모듈 (Hub Detection Module)
=============================================================================

노드 밀집도 + 이동 방향 분산으로 허브(로비, 교차점)를 발견하고 정리한다.

[허브의 정의]
여러 방향에서 노드가 밀집하는 영역.
- 복도: 밀집 O, 방향 1~2개 → 허브 아님
- T자 교차점: 밀집 O, 방향 3개 → 허브
- 로비: 밀집 O, 방향 다양 → 허브

[허브 정리]
1. 경계 노드 식별: 이전/다음 프레임 노드가 허브 밖 → 진입/출구
2. 내부 노드 삭제
3. 경계 노드끼리 직선 거리로 엣지 연결 (허브 내 자유 이동)

[엣지케이스 대응]
- 방향 = pose 이동 벡터 (rotation 아님) → 카메라 회전에 의한 오탐 방지
- 스캔 시작/끝이 허브 안이면 무조건 경계 노드로 처리
- 경계 노드 2개 미만이면 허브로 처리하지 않음
"""

import numpy as np
from typing import List, Dict, Tuple, Set
from scipy.spatial import cKDTree


# =============================================================================
# 상수
# =============================================================================

HUB_RADIUS = 3.0                 # 허브 탐지 반경 (미터)
MIN_NEARBY_NODES = 8             # 밀집 판정 최소 이웃 노드 수
MIN_DIRECTION_CLUSTERS = 3      # 허브 판정 최소 방향 클러스터 수
DIRECTION_ANGLE_THRESHOLD = 30.0 # 방향 클러스터링 각도 임계값 (도)
HUB_MERGE_RADIUS = 3.0          # 인접 허브 병합 반경 (미터)
MIN_BOUNDARY_NODES = 2           # 허브 최소 경계 노드 수


# =============================================================================
# 허브 탐지
# =============================================================================

def find_hub_zones(
    nodes: List[Dict],
    radius: float = HUB_RADIUS,
    min_nearby: int = MIN_NEARBY_NODES,
    min_directions: int = MIN_DIRECTION_CLUSTERS,
    angle_threshold: float = DIRECTION_ANGLE_THRESHOLD
) -> List[Dict]:
    """
    노드 밀집 + 방향 분산으로 허브 영역을 탐지한다.

    [알고리즘]
    1. KD-Tree로 각 노드 주변 반경 내 이웃 노드 검색
    2. 이웃 수 >= min_nearby 인 노드 = 밀집 후보
    3. 이웃들의 이동 방향을 클러스터링
    4. 방향 클러스터 >= min_directions → 허브 노드로 마킹
    5. 인접 허브 노드를 하나의 허브 영역으로 병합

    Args:
        nodes: 노드 리스트 (position, direction 포함)
        radius: 이웃 탐색 반경
        min_nearby: 밀집 판정 최소 이웃 수
        min_directions: 허브 판정 최소 방향 클러스터 수
        angle_threshold: 방향 클러스터링 각도

    Returns:
        허브 영역 리스트. 각 허브:
        - center: [x, y, z] 중심 좌표
        - node_indices: 허브에 속하는 노드 인덱스들
        - boundary_indices: 경계 노드 인덱스들
        - direction_count: 감지된 방향 수
    """
    if len(nodes) < min_nearby:
        return []

    positions = np.array([n['position'][:2] for n in nodes])
    tree = cKDTree(positions)

    # Step 1: 각 노드에서 밀집 + 방향 분산 체크
    hub_candidates = set()

    for i in range(len(nodes)):
        # 반경 내 이웃 검색
        neighbor_indices = tree.query_ball_point(positions[i], radius)

        if len(neighbor_indices) < min_nearby:
            continue

        # 이웃들의 이동 방향 수집 (자기 자신 제외)
        directions = []
        for j in neighbor_indices:
            if j == i:
                continue
            d = nodes[j]['direction']
            norm = np.linalg.norm(d)
            if norm > 1e-6:
                directions.append(d / norm)

        if len(directions) < min_directions:
            continue

        # 방향 클러스터링
        n_clusters = _count_direction_clusters(directions, angle_threshold)

        if n_clusters >= min_directions:
            hub_candidates.add(i)

    if not hub_candidates:
        return []

    # Step 2: 인접 허브 노드를 영역으로 병합
    hubs = _merge_hub_nodes(
        list(hub_candidates), positions, radius=HUB_MERGE_RADIUS
    )

    # Step 3: 각 허브 영역에 반경 내 모든 노드를 포함
    hub_zones = []
    for hub_indices in hubs:
        # 허브 중심
        hub_positions = positions[hub_indices]
        center_xy = np.mean(hub_positions, axis=0)

        # 허브 반경 내 모든 노드 포함
        all_in_hub = set()
        for idx in hub_indices:
            nearby = tree.query_ball_point(positions[idx], radius)
            all_in_hub.update(nearby)

        # 중심 Z값 (평균)
        z_values = [nodes[i]['position'][2] for i in all_in_hub]
        center_z = float(np.mean(z_values))

        hub_zones.append({
            'center': np.array([center_xy[0], center_xy[1], center_z]),
            'node_indices': sorted(all_in_hub),
            'direction_count': len(hub_indices),
        })

    return hub_zones


def _count_direction_clusters(
    directions: List[np.ndarray],
    angle_threshold: float
) -> int:
    """
    방향 벡터들을 각도 기반으로 클러스터링하고 클러스터 수를 반환한다.

    [알고리즘]
    각 방향을 각도(0°~360°)로 변환 후,
    angle_threshold 이내의 각도끼리 같은 클러스터로 그룹화.
    반대 방향(180° 차이)은 같은 클러스터로 처리 (왕복 이동 고려).
    """
    if not directions:
        return 0

    # 방향 → 각도 (0°~180°, 반대 방향은 같은 것으로)
    angles = []
    for d in directions:
        angle = np.degrees(np.arctan2(d[1], d[0]))
        # 0°~180° 범위로 정규화 (반대 방향 = 같은 축)
        angle = angle % 180.0
        angles.append(angle)

    angles.sort()

    # 그리디 클러스터링
    clusters = []
    used = set()

    for i, a in enumerate(angles):
        if i in used:
            continue

        cluster = [a]
        used.add(i)

        for j in range(i + 1, len(angles)):
            if j in used:
                continue

            # 각도 차이 (원형 거리)
            diff = min(abs(angles[j] - a), 180.0 - abs(angles[j] - a))

            if diff <= angle_threshold:
                cluster.append(angles[j])
                used.add(j)

        clusters.append(cluster)

    return len(clusters)


def _merge_hub_nodes(
    hub_indices: List[int],
    positions: np.ndarray,
    radius: float
) -> List[List[int]]:
    """인접한 허브 노드들을 하나의 영역으로 병합한다 (Union-Find)."""
    if not hub_indices:
        return []

    hub_positions = positions[hub_indices]
    tree = cKDTree(hub_positions)

    # Union-Find
    parent = list(range(len(hub_indices)))

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(a, b):
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[ra] = rb

    # 반경 내 허브 노드끼리 병합
    for i in range(len(hub_indices)):
        nearby = tree.query_ball_point(hub_positions[i], radius)
        for j in nearby:
            if j != i:
                union(i, j)

    # 그룹별로 분류
    groups = {}
    for i in range(len(hub_indices)):
        root = find(i)
        if root not in groups:
            groups[root] = []
        groups[root].append(hub_indices[i])

    return list(groups.values())


# =============================================================================
# 허브 정리
# =============================================================================

def cleanup_hubs(
    nodes: List[Dict],
    hub_zones: List[Dict]
) -> Tuple[List[Dict], List[Dict]]:
    """
    허브 내부 노드를 제거하고 경계 노드만 유지한다.

    [경계 노드 판정]
    시간순(프레임 순서)으로 이전/다음 노드가 허브 밖에 있으면 경계 노드.
    스캔 시작/끝 프레임은 무조건 경계 노드.

    [정리 결과]
    - 경계 노드: 유지
    - 내부 노드: 삭제
    - 경계 노드끼리 직선 엣지 추가 (허브 내 자유 이동)

    Args:
        nodes: 전체 노드 리스트
        hub_zones: 허브 영역 리스트

    Returns:
        (유지할 노드 인덱스 리스트, 허브 엣지 정보 리스트)
    """
    n_nodes = len(nodes)
    remove_indices = set()
    hub_edges = []

    for hub in hub_zones:
        hub_set = set(hub['node_indices'])

        # 경계 노드 식별
        boundary = set()

        for idx in hub['node_indices']:
            # 스캔 시작/끝은 무조건 경계
            if idx == 0 or idx == n_nodes - 1:
                boundary.add(idx)
                continue

            # 이전 프레임이 허브 밖
            if idx - 1 not in hub_set:
                boundary.add(idx)
            # 다음 프레임이 허브 밖
            if idx + 1 not in hub_set:
                boundary.add(idx)

        # 경계 노드 2개 미만이면 허브로 처리하지 않음
        if len(boundary) < MIN_BOUNDARY_NODES:
            continue

        hub['boundary_indices'] = sorted(boundary)

        # 내부 노드 = 허브 노드 - 경계 노드
        interior = hub_set - boundary
        remove_indices.update(interior)

        # 경계 노드끼리 완전 그래프 엣지 생성
        boundary_list = sorted(boundary)
        for i in range(len(boundary_list)):
            for j in range(i + 1, len(boundary_list)):
                idx_a = boundary_list[i]
                idx_b = boundary_list[j]
                pos_a = nodes[idx_a]['position']
                pos_b = nodes[idx_b]['position']
                dist = float(np.linalg.norm(pos_a[:2] - pos_b[:2]))

                hub_edges.append({
                    'from_idx': idx_a,
                    'to_idx': idx_b,
                    'distance': dist,
                    'edge_type': 'HUB',
                })

    # 유지할 인덱스 = 전체 - 제거 대상
    keep_indices = [i for i in range(n_nodes) if i not in remove_indices]

    return keep_indices, hub_edges
