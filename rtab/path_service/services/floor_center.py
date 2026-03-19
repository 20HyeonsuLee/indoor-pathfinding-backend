"""
=============================================================================
바닥 중앙 노드 생성 모듈 (Floor Center Node Generation Module)
=============================================================================

포인트 클라우드에서 바닥 범위를 추출하고, 이동 방향에 수직인 폭의 중앙에
노드를 배치한다.

[핵심 아이디어]
pose 궤적은 스캔자의 실제 걸음 경로이므로 넓은 공간에서는 지그재그가 된다.
바닥 중앙은 공간 구조에서 결정되므로, 같은 복도를 왕복해도 같은 위치에 수렴한다.

[알고리즘]
1. 프레임별 포인트 클라우드 추출 (DB의 Data.scan)
2. 주변 ±N 프레임의 포인트를 합산 (FOV 한계 보완)
3. 바닥 필터: 현재 pose Z ± tolerance 이내
4. 이동 방향의 수직 방향으로 바닥 폭 측정
5. 바닥 범위의 중앙점 = 노드 위치

[폴백]
- 바닥 포인트 부족 → pose 위치로 폴백
- 이동 방향 = 0 (정지) → rotation 행렬의 forward 벡터로 폴백
"""

import sqlite3
import struct
import zlib
import numpy as np
from typing import List, Dict, Tuple, Optional


# =============================================================================
# 상수
# =============================================================================

GROUP_SIZE = 10              # 주변 ±N 프레임 그룹핑
FLOOR_Z_TOLERANCE = 0.3     # 바닥 판정 Z 허용 범위 (미터)
MIN_FLOOR_POINTS = 15       # 바닥 분석 최소 포인트 수
ZERO_VELOCITY_THRESHOLD = 0.01  # 정지 판정 임계값 (미터)
FLOOR_SEARCH_BELOW = 2.5    # 카메라 아래 바닥 탐색 최대 거리 (미터)
FLOOR_SEARCH_ABOVE = 0.1    # 카메라 위 바닥 허용 (약간의 센서 노이즈)
MERGE_RADIUS = 1.0           # 중복 노드 병합 반경 (미터)


# =============================================================================
# DB에서 프레임별 데이터 추출
# =============================================================================

def extract_per_frame_data(db_path: str) -> List[Dict]:
    """
    RTAB-Map DB에서 프레임별 pose + 포인트 클라우드를 추출한다.

    [데이터 소스]
    - Node 테이블: pose (3x4 변환 행렬)
    - Data 테이블: scan (zlib 압축 4채널 float32), scan_info (센서→카메라 변환)

    Returns:
        프레임 리스트. 각 프레임:
        - node_id: 노드 ID
        - pose: 3x4 변환 행렬
        - position: [x, y, z] 월드 좌표
        - rotation: 3x3 회전 행렬
        - points_local: [M, 3] 카메라 로컬 좌표의 포인트 (없으면 None)
        - local_tf: 3x4 센서→카메라 변환 행렬
    """
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()

    # 모든 노드의 pose
    cursor.execute('SELECT id, pose FROM Node ORDER BY id')
    node_rows = cursor.fetchall()

    poses = {}
    for node_id, pose_blob in node_rows:
        if not pose_blob or len(pose_blob) != 48:
            continue
        matrix = np.array(struct.unpack('12f', pose_blob)).reshape(3, 4)
        if np.isfinite(matrix).all() and not np.allclose(matrix, 0):
            poses[node_id] = matrix

    # Data.scan 로드 (프레임별)
    cursor.execute(
        'SELECT d.id, d.scan, d.scan_info '
        'FROM Data d WHERE d.scan IS NOT NULL AND LENGTH(d.scan) > 50'
    )
    scan_data = {}
    for nid, scan_blob, scan_info_blob in cursor.fetchall():
        scan_data[nid] = (scan_blob, scan_info_blob)

    conn.close()

    # 프레임 리스트 조립
    frames = []
    for node_id in sorted(poses.keys()):
        pose = poses[node_id]
        position = pose[:, 3].copy()
        rotation = pose[:, :3].copy()

        # 포인트 클라우드 디코딩
        points_local = None
        local_tf = np.eye(3, 4)

        if node_id in scan_data:
            scan_blob, scan_info_blob = scan_data[node_id]

            # scan_info에서 local transform 추출
            if scan_info_blob and len(scan_info_blob) >= 72:
                lt = np.array(struct.unpack_from('12f', scan_info_blob, 24)).reshape(3, 4)
                if np.isfinite(lt).all():
                    local_tf = lt

            try:
                decompressed = zlib.decompress(scan_blob)
                n_pts = len(decompressed) // 16
                if n_pts > 0:
                    pts = np.frombuffer(decompressed, dtype=np.float32).reshape(-1, 4)[:, :3]
                    # 센서 로컬 → 카메라 로컬 변환
                    points_local = (local_tf[:, :3] @ pts.T).T + local_tf[:, 3]
            except (zlib.error, ValueError):
                pass

        frames.append({
            'node_id': node_id,
            'pose': pose,
            'position': position,
            'rotation': rotation,
            'points_local': points_local,
            'local_tf': local_tf,
        })

    return frames


# =============================================================================
# 바닥 중앙 노드 계산
# =============================================================================

SPATIAL_RADIUS = 2.0         # 2차 패스 공간 검색 반경 (미터)


def compute_floor_center_nodes(
    frames: List[Dict],
    group_size: int = GROUP_SIZE,
    floor_z_tolerance: float = FLOOR_Z_TOLERANCE,
    min_floor_points: int = MIN_FLOOR_POINTS,
    spatial_radius: float = SPATIAL_RADIUS
) -> List[Dict]:
    """
    2-pass로 각 프레임의 바닥 중앙 노드를 계산한다.

    [1차 pass - 시간순 이웃]
    주변 ±group_size 프레임의 포인트 클라우드를 합산하여 대략적인 바닥 중앙 산출.
    카메라 FOV 한계로 한쪽만 보일 수 있어 중앙이 치우칠 수 있음.

    [2차 pass - 공간적 이웃]
    1차 결과의 XY 위치 기반으로, 시간과 무관하게 공간적으로 가까운
    (spatial_radius 이내) 모든 프레임의 포인트를 합산하여 재계산.
    나중에 같은 위치를 반대 방향에서 지나간 프레임이 있으면
    양쪽 바닥 경계를 모두 반영하여 정확한 중앙을 산출한다.

    Returns:
        노드 리스트. 각 노드:
        - position: [x, y, z] 바닥 중앙 좌표 (Z = 바닥 높이)
        - direction: [dx, dy] 이동 방향 (정규화)
        - floor_width: 바닥 폭 (미터)
        - is_fallback: pose 폴백 여부
        - node_id: 원본 DB 노드 ID
    """
    from scipy.spatial import cKDTree

    n_frames = len(frames)

    # --- Step 0: 바닥 Z 높이 추정 ---
    floor_z_estimates = _estimate_floor_z_per_frame(frames)

    # =================================================================
    # 1차 pass: 시간순 ±N 이웃
    # =================================================================
    nodes_pass1 = []
    for i in range(n_frames):
        current = frames[i]
        pose_pos = current['position']
        floor_z = floor_z_estimates[i]

        world_floor_points = _gather_floor_points(
            frames, i, group_size, floor_z, floor_z_tolerance
        )
        direction_xy = _compute_movement_direction(frames, i)

        if len(world_floor_points) >= min_floor_points:
            center_xy, floor_width = _compute_floor_center(
                world_floor_points, pose_pos[:2], direction_xy
            )
            node_position = np.array([center_xy[0], center_xy[1], floor_z])
            is_fallback = False
        else:
            node_position = np.array([pose_pos[0], pose_pos[1], floor_z])
            floor_width = 0.0
            is_fallback = True

        nodes_pass1.append({
            'position': node_position,
            'direction': direction_xy,
            'floor_width': float(floor_width),
            'is_fallback': is_fallback,
            'node_id': current['node_id'],
        })

    # =================================================================
    # 2차 pass: 공간적 이웃 (1차 위치 기반)
    # =================================================================
    # 1차 결과의 XY 위치로 KD-Tree 구축
    pass1_xy = np.array([n['position'][:2] for n in nodes_pass1])
    tree = cKDTree(pass1_xy)

    nodes_pass2 = []
    improved_count = 0

    for i in range(n_frames):
        current = frames[i]
        pose_pos = current['position']
        floor_z = floor_z_estimates[i]
        direction_xy = nodes_pass1[i]['direction']

        # 공간적으로 가까운 프레임 인덱스 (시간 무관)
        spatial_neighbors = tree.query_ball_point(pass1_xy[i], spatial_radius)

        # 공간적 이웃의 포인트 클라우드 합산
        all_floor_pts = []
        for j in spatial_neighbors:
            pts_local = frames[j]['points_local']
            if pts_local is None or len(pts_local) == 0:
                continue

            pose_j = frames[j]['pose']
            r = pose_j[:, :3]
            t = pose_j[:, 3]
            pts_world = (r @ pts_local.T).T + t

            z_mask = np.abs(pts_world[:, 2] - floor_z) <= floor_z_tolerance
            floor_pts = pts_world[z_mask]
            if len(floor_pts) > 0:
                all_floor_pts.append(floor_pts)

        if all_floor_pts:
            world_floor_points = np.vstack(all_floor_pts)
        else:
            world_floor_points = np.empty((0, 3))

        if len(world_floor_points) >= min_floor_points:
            center_xy, floor_width = _compute_floor_center(
                world_floor_points, pose_pos[:2], direction_xy
            )
            node_position = np.array([center_xy[0], center_xy[1], floor_z])
            is_fallback = False

            # 1차 대비 개선 여부 체크
            if nodes_pass1[i]['is_fallback'] or floor_width > nodes_pass1[i]['floor_width'] * 1.1:
                improved_count += 1
        else:
            # 2차에서도 부족하면 1차 결과 유지
            node_position = nodes_pass1[i]['position'].copy()
            floor_width = nodes_pass1[i]['floor_width']
            is_fallback = nodes_pass1[i]['is_fallback']

        nodes_pass2.append({
            'position': node_position,
            'direction': direction_xy,
            'floor_width': float(floor_width),
            'is_fallback': is_fallback,
            'node_id': current['node_id'],
        })

    if improved_count > 0:
        print(f"  2차 패스: {improved_count}개 노드 개선 "
              f"({100*improved_count/n_frames:.0f}%)")

    return nodes_pass2


def _estimate_floor_z_per_frame(frames: List[Dict]) -> List[float]:
    """
    각 프레임의 바닥 Z 높이를 추정한다.

    [알고리즘]
    1. 각 프레임에서 카메라 아래쪽 포인트의 하위 밀집 구간으로 바닥 Z 추정
    2. 프레임별 바닥 Z를 클러스터링하여 층별 고정 Z 결정
       (같은 층의 바닥 Z는 동일해야 하므로)
    3. 각 프레임을 가장 가까운 층에 할당

    Returns:
        프레임별 바닥 Z 높이 리스트 (같은 층이면 동일한 값)
    """
    n = len(frames)
    raw_floor_z = np.full(n, np.nan)

    for i in range(n):
        pts_local = frames[i]['points_local']
        if pts_local is None or len(pts_local) == 0:
            continue

        pose = frames[i]['pose']
        r = pose[:, :3]
        t = pose[:, 3]
        pts_world = (r @ pts_local.T).T + t
        cam_z = frames[i]['position'][2]

        # 카메라 아래쪽만 (천장 제외)
        relative_z = pts_world[:, 2] - cam_z
        below_mask = (relative_z < FLOOR_SEARCH_ABOVE) & (relative_z > -FLOOR_SEARCH_BELOW)
        below_z = pts_world[below_mask, 2]

        if len(below_z) < 3:
            continue

        # 바닥 = 아래쪽 포인트 중 하위 20%의 중앙값
        p20 = np.percentile(below_z, 20)
        floor_candidates = below_z[below_z <= p20]
        if len(floor_candidates) > 0:
            raw_floor_z[i] = float(np.median(floor_candidates))

    # NaN 보간
    valid_mask = ~np.isnan(raw_floor_z)
    if not valid_mask.any():
        pose_zs = np.array([f['position'][2] for f in frames])
        median_z = float(np.median(pose_zs))
        return [median_z] * n

    valid_indices = np.where(valid_mask)[0]
    valid_values = raw_floor_z[valid_mask]

    for i in range(n):
        if np.isnan(raw_floor_z[i]):
            nearest = valid_indices[np.argmin(np.abs(valid_indices - i))]
            raw_floor_z[i] = raw_floor_z[nearest]

    # 층별 고정 Z 결정: 바닥 Z 값들을 클러스터링
    # 같은 층의 바닥 높이는 ±0.5m 이내로 일치해야 함
    floor_levels = _cluster_floor_z_values(raw_floor_z, merge_threshold=1.0)

    # 각 프레임을 가장 가까운 층에 할당
    result = []
    for i in range(n):
        best_level = min(floor_levels, key=lambda z: abs(z - raw_floor_z[i]))
        result.append(best_level)

    return result


def _cluster_floor_z_values(z_values: np.ndarray, merge_threshold: float = 1.0) -> List[float]:
    """
    바닥 Z 값들을 클러스터링하여 층별 고정 높이를 결정한다.

    가까운 값(merge_threshold 이내)을 같은 클러스터로 묶고,
    각 클러스터의 중앙값을 층 높이로 사용한다.
    """
    sorted_z = np.sort(z_values)

    clusters = [[sorted_z[0]]]
    for z in sorted_z[1:]:
        if abs(z - np.median(clusters[-1])) <= merge_threshold:
            clusters[-1].append(z)
        else:
            clusters.append([z])

    return [float(np.median(c)) for c in clusters]


def _gather_floor_points(
    frames: List[Dict],
    center_idx: int,
    group_size: int,
    floor_z: float,
    z_tolerance: float
) -> np.ndarray:
    """
    주변 ±group_size 프레임의 포인트를 월드 좌표로 변환하고
    바닥 높이(floor_z ± z_tolerance)로 필터링한다.

    floor_z는 바닥의 절대 Z 높이 (카메라 Z가 아님).
    """
    n = len(frames)
    start = max(0, center_idx - group_size)
    end = min(n, center_idx + group_size + 1)

    all_floor_pts = []
    for j in range(start, end):
        pts_local = frames[j]['points_local']
        if pts_local is None or len(pts_local) == 0:
            continue

        # 카메라 로컬 → 월드 변환
        pose = frames[j]['pose']
        r = pose[:, :3]
        t = pose[:, 3]
        pts_world = (r @ pts_local.T).T + t

        # 바닥 필터: Z값이 바닥 높이 ± tolerance 이내
        z_mask = np.abs(pts_world[:, 2] - floor_z) <= z_tolerance
        floor_pts = pts_world[z_mask]

        if len(floor_pts) > 0:
            all_floor_pts.append(floor_pts)

    if all_floor_pts:
        return np.vstack(all_floor_pts)
    return np.empty((0, 3))


def _compute_movement_direction(
    frames: List[Dict],
    idx: int
) -> np.ndarray:
    """
    프레임 idx의 이동 방향을 계산한다.

    [우선순위]
    1. pose 간 이동 벡터 (앞뒤 프레임 차이)
    2. 폴백: rotation 행렬의 forward 벡터 (3번째 열)
    """
    n = len(frames)

    # 방법 1: 앞뒤 프레임 간 이동 벡터
    prev_idx = max(0, idx - 3)
    next_idx = min(n - 1, idx + 3)

    if next_idx > prev_idx:
        movement = frames[next_idx]['position'][:2] - frames[prev_idx]['position'][:2]
        norm = np.linalg.norm(movement)

        if norm > ZERO_VELOCITY_THRESHOLD:
            return movement / norm

    # 방법 2: rotation forward 벡터 (정지 상태)
    rotation = frames[idx]['rotation']
    # RTAB-Map에서 카메라 forward = rotation의 3번째 열 (Z축)
    forward_3d = rotation[:, 2]
    forward_xy = forward_3d[:2]
    norm = np.linalg.norm(forward_xy)

    if norm > 1e-6:
        return forward_xy / norm

    # 최후 폴백: X축 방향
    return np.array([1.0, 0.0])


def _compute_floor_center(
    floor_points: np.ndarray,
    camera_xy: np.ndarray,
    direction_xy: np.ndarray
) -> Tuple[np.ndarray, float]:
    """
    바닥 포인트에서 이동 방향 수직 폭의 중앙점을 계산한다.

    [알고리즘]
    1. 수직 방향 = direction_xy를 90° 회전
    2. 모든 바닥 포인트를 수직 방향으로 투영
    3. 투영의 min/max = 바닥 범위
    4. 범위의 중앙 = 카메라 위치에서 수직 방향으로 이동

    Args:
        floor_points: [N, 3] 바닥 포인트 (월드 좌표)
        camera_xy: [2] 카메라 XY 위치
        direction_xy: [2] 이동 방향 (정규화)

    Returns:
        (center_xy, floor_width)
    """
    # 수직 방향 (이동 방향을 90° 회전)
    perp = np.array([-direction_xy[1], direction_xy[0]])

    # 카메라 위치를 원점으로 한 바닥 포인트의 수직 방향 투영
    relative_xy = floor_points[:, :2] - camera_xy
    projections = relative_xy @ perp  # 각 포인트의 수직 방향 거리

    # 이상치 제거: 1-99 퍼센타일
    p1, p99 = np.percentile(projections, [1, 99])
    trimmed = projections[(projections >= p1) & (projections <= p99)]

    if len(trimmed) < 5:
        trimmed = projections

    # 바닥 범위
    floor_min = trimmed.min()
    floor_max = trimmed.max()
    floor_width = floor_max - floor_min

    # 중앙 오프셋
    center_offset = (floor_max + floor_min) / 2.0

    # 중앙 위치 = 카메라 + 오프셋 × 수직방향
    center_xy = camera_xy + center_offset * perp

    return center_xy, floor_width


# =============================================================================
# 중복 노드 병합
# =============================================================================

def merge_nearby_nodes(
    nodes: List[Dict],
    merge_radius: float = MERGE_RADIUS
) -> List[Dict]:
    """
    경로 순서를 유지하면서, 이미 유지된 노드 근처의 새 노드를 스킵한다.

    [핵심]
    경로를 따라 순회하면서, 각 노드가 이미 유지된 노드의 merge_radius
    이내에 있으면:
    - 기존 노드보다 floor_width가 넓으면 → 기존을 교체
    - 아니면 → 스킵 (기존이 더 정확)

    이 방식은 Union-Find와 달리 전이적 체이닝이 없어서
    먼 노드끼리 합쳐지지 않는다.

    Args:
        nodes: 노드 리스트 (position, floor_width 포함)
        merge_radius: 병합 반경 (미터)

    Returns:
        중복 제거된 노드 리스트 (경로 순서 유지)
    """
    if len(nodes) < 2:
        return nodes

    kept = [nodes[0]]  # 첫 노드는 무조건 유지

    for i in range(1, len(nodes)):
        current = nodes[i]
        current_xy = current['position'][:2]

        # 이미 유지된 노드 중 가장 가까운 것 찾기
        best_dist = float('inf')
        best_idx = -1
        for j, k in enumerate(kept):
            dist = np.linalg.norm(current_xy - k['position'][:2])
            if dist < best_dist:
                best_dist = dist
                best_idx = j

        if best_dist <= merge_radius:
            # 근처에 기존 노드 있음 → 더 좋은 쪽만 남기기
            if current['floor_width'] > kept[best_idx]['floor_width']:
                # 새 노드가 더 정확 → 기존 교체
                kept[best_idx] = current
            # 아니면 스킵 (기존이 더 좋음)
        else:
            # 근처에 없음 → 새로 유지
            kept.append(current)

    return kept
