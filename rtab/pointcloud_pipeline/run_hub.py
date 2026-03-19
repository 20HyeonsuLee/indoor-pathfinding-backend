"""
포인트 클라우드 기반 실내 경로 그래프 생성 파이프라인

[기존 방식과의 차이]
  기존: 카메라 궤적(선) → 선 위에서 노드/엣지 추출
  이것: 포인트 클라우드(공간) → 2D 점유 격자 → 복도 중심선 → 그래프

[파이프라인]
  1. DB에서 3D 포인트 클라우드 추출
     - Feature 테이블의 depth_x/y/z (카메라 로컬 좌표)
     - Node 테이블의 pose (3x4 변환 행렬)로 월드 좌표 변환
     - world = R @ local + t

  2. 층 분리
     - Z값 히스토그램으로 바닥 높이 탐지 (기존과 동일)
     - 각 포인트를 가장 가까운 층에 할당

  3. 층별 2D 점유 격자 (Occupancy Grid) 생성
     - XY 평면을 cell_size(0.1m) 격자로 나눔
     - 각 셀에 포인트가 있으면 OCCUPIED(벽/장애물)
     - 포인트가 없으면 FREE(통행 가능) 또는 UNKNOWN

  4. 통행 가능 영역 추출
     - 카메라 궤적이 지나간 셀 = 확실한 FREE
     - FREE 영역에서 벽(OCCUPIED)을 제외한 연결 영역 추출

  5. 스켈레톤화 (Thinning)
     - 통행 가능 영역을 1픽셀 폭의 중심선으로 축소
     - 넓은 복도든 좁은 복도든 중심선 1개가 나옴

  6. 그래프 변환
     - 스켈레톤의 분기점 = JUNCTION 노드
     - 스켈레톤의 끝점 = ENDPOINT 노드
     - 분기점 사이의 경로 = 엣지 (거리 = 픽셀 수 × cell_size)

  7. 허브(로비) 감지 + 완전 그래프 생성
     - distance_transform으로 각 셀의 벽까지 거리 계산
     - 넓은 열린 공간(로비) = 벽 거리 > 임계값인 연결 영역
     - 열린 공간 경계에서 복도가 진입하는 지점 탐지
     - 진입점들 사이에 완전 그래프(모든 쌍 연결) 엣지 생성
     → 로비에 3개 복도 연결 시: 3개 엣지 (1↔2, 1↔3, 2↔3)
"""
import sqlite3
import struct
import os
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from scipy.ndimage import gaussian_filter1d, binary_dilation, binary_erosion, label
from scipy.ndimage import distance_transform_edt
from collections import deque

DB_PATH = os.path.join(os.path.dirname(__file__), '..', 'db', 'hub.db')
OUTPUT_DIR = os.path.join(os.path.dirname(__file__), '..', 'exported_images', 'pointcloud')
os.makedirs(OUTPUT_DIR, exist_ok=True)

CELL_SIZE = 0.15  # 점유 격자 해상도 (미터/셀)


# =============================================================================
# Step 1: DB에서 3D 포인트 클라우드 추출
# =============================================================================

def extract_pointcloud(db_path):
    """
    RTAB-Map DB에서 Feature 테이블의 3D 좌표를 추출하고
    Node의 pose로 월드 좌표계로 변환한다.

    Feature.depth_x/y/z는 카메라 로컬 좌표이므로:
      world_point = R @ local_point + t
    여기서 R = pose[:, :3] (3x3 회전), t = pose[:, 3] (3x1 이동)
    """
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()

    # 모든 노드의 pose 로드
    cursor.execute('SELECT id, pose FROM Node ORDER BY id')
    poses = {}
    cam_positions = []
    for node_id, pose_blob in cursor.fetchall():
        if pose_blob and len(pose_blob) == 48:
            matrix = np.array(struct.unpack('12f', pose_blob)).reshape(3, 4)
            if np.isfinite(matrix).all() and not np.allclose(matrix, 0):
                poses[node_id] = matrix
                cam_positions.append(matrix[:, 3])

    cam_positions = np.array(cam_positions)

    # --- 소스 1: Feature 3D 좌표 (시각 특징점, 희소) ---
    cursor.execute(
        'SELECT node_id, depth_x, depth_y, depth_z '
        'FROM Feature WHERE depth_x != 0 AND depth_y != 0 AND depth_z != 0'
    )

    world_points = []
    for node_id, dx, dy, dz in cursor.fetchall():
        if node_id not in poses:
            continue
        matrix = poses[node_id]
        local = np.array([dx, dy, dz])
        world = matrix[:, :3] @ local + matrix[:, 3]
        if np.isfinite(world).all():
            world_points.append(world)

    feature_count = len(world_points)

    # --- 소스 2: Data.scan (zlib 압축된 4채널 포인트, 더 밀집) ---
    import zlib
    cursor.execute(
        'SELECT d.id, d.scan, d.scan_info, n.pose '
        'FROM Data d JOIN Node n ON d.id = n.id '
        'WHERE d.scan IS NOT NULL AND LENGTH(d.scan) > 50'
    )

    scan_count = 0
    for nid, scan_blob, scan_info_blob, pose_blob in cursor.fetchall():
        if not pose_blob or len(pose_blob) != 48:
            continue
        pose = np.array(struct.unpack('12f', pose_blob)).reshape(3, 4)
        if not np.isfinite(pose).all() or np.allclose(pose, 0):
            continue

        # scan_info에서 local transform 추출 (센서→카메라 좌표 변환)
        local_tf = np.eye(3, 4)
        if scan_info_blob and len(scan_info_blob) >= 72:
            lt = np.array(struct.unpack_from('12f', scan_info_blob, 24)).reshape(3, 4)
            if np.isfinite(lt).all():
                local_tf = lt

        try:
            decompressed = zlib.decompress(scan_blob)
            n_pts = len(decompressed) // 16  # 4채널 × 4바이트
            if n_pts < 1:
                continue
            pts = np.frombuffer(decompressed, dtype=np.float32).reshape(-1, 4)[:, :3]

            for p in pts:
                # scan local → camera local → world
                p_cam = local_tf[:, :3] @ p + local_tf[:, 3]
                p_world = pose[:, :3] @ p_cam + pose[:, 3]
                if np.isfinite(p_world).all():
                    world_points.append(p_world)
                    scan_count += 1
        except (zlib.error, ValueError):
            continue

    conn.close()

    print(f"  Feature 포인트: {feature_count}개")
    print(f"  Scan 포인트: {scan_count}개")

    world_points = np.array(world_points)
    print(f"  포인트 클라우드: {len(world_points)}개")
    print(f"  카메라 궤적: {len(cam_positions)}개")
    print(f"  X: {world_points[:, 0].min():.1f} ~ {world_points[:, 0].max():.1f}m")
    print(f"  Y: {world_points[:, 1].min():.1f} ~ {world_points[:, 1].max():.1f}m")
    print(f"  Z: {world_points[:, 2].min():.1f} ~ {world_points[:, 2].max():.1f}m")

    return world_points, cam_positions


# =============================================================================
# Step 2: 층 분리
# =============================================================================

def separate_floors_by_z(points, cam_positions, floor_height=3.0):
    """
    Z값 히스토그램 피크로 층을 분리한다.
    포인트와 카메라 궤적 모두에 대해 층 할당을 수행한다.
    """
    z_values = points[:, 2]
    z_min, z_max = z_values.min(), z_values.max()

    # 히스토그램 피크 탐지
    # 핵심: sigma를 작게 하여 인접 층의 피크가 합쳐지지 않도록 한다.
    # bin_size=0.3m, sigma=0.8 → 실질 스무딩 범위 ±0.72m (층고 3m 대비 충분히 좁음)
    bin_size = 0.3
    num_bins = max(int((z_max - z_min) / bin_size), 10)
    hist, bin_edges = np.histogram(z_values, bins=num_bins)
    bin_centers = (bin_edges[:-1] + bin_edges[1:]) / 2
    smoothed = gaussian_filter1d(hist.astype(float), sigma=0.8)

    # 유의미한 빈(min_count 이상)의 연속 그룹을 찾고,
    # 각 그룹의 가중 평균을 피크로 사용한다.
    # find_peaks보다 plateau(넓은 봉우리)에 강건하다.
    min_count = max(len(z_values) * 0.02, 3)
    min_peak_dist = floor_height * 0.7  # 2.1m

    significant = smoothed >= min_count
    # 연속 빈 그룹 찾기
    groups = []
    in_group = False
    for i in range(len(significant)):
        if significant[i] and not in_group:
            group_start = i
            in_group = True
        elif not significant[i] and in_group:
            groups.append((group_start, i))
            in_group = False
    if in_group:
        groups.append((group_start, len(significant)))

    # 각 그룹의 가중 평균 = 층 높이
    peaks = []
    for gs, ge in groups:
        region_centers = bin_centers[gs:ge]
        region_weights = smoothed[gs:ge]
        if region_weights.sum() < min_count:
            continue
        peak_z = float(np.average(region_centers, weights=region_weights))
        # 기존 피크와 min_peak_dist 이상 떨어져야 새 층
        if all(abs(peak_z - p) >= min_peak_dist for p in peaks):
            peaks.append(peak_z)

    if not peaks:
        peaks = [float(np.median(z_values))]

    peaks.sort()
    print(f"  층 피크: {[f'{p:.1f}m' for p in peaks]}")

    # 각 포인트를 가장 가까운 피크에 할당
    peaks_arr = np.array(peaks)
    floor_assignments = np.argmin(np.abs(z_values[:, None] - peaks_arr[None, :]), axis=1)

    # 카메라 궤적도 층 할당
    cam_z = cam_positions[:, 2]
    cam_floors = np.argmin(np.abs(cam_z[:, None] - peaks_arr[None, :]), axis=1)

    # 층별로 분리
    floors = {}
    for fi in range(len(peaks)):
        mask = floor_assignments == fi
        cam_mask = cam_floors == fi
        if mask.sum() < 50:
            continue
        floors[fi] = {
            'points': points[mask],
            'cam_positions': cam_positions[cam_mask] if cam_mask.sum() > 0 else np.empty((0, 3)),
            'z_peak': peaks[fi],
            'point_count': int(mask.sum()),
            'cam_count': int(cam_mask.sum()),
        }
        print(f"  {fi+1}층 (z={peaks[fi]:.1f}m): 포인트 {mask.sum()}개, 궤적 {cam_mask.sum()}개")

    return floors, peaks


# =============================================================================
# Step 3: 2D 점유 격자 생성
# =============================================================================

def create_occupancy_grid(floor_data, cell_size=CELL_SIZE):
    """
    3D 포인트를 XY 평면에 투영하여 2D 점유 격자를 생성한다.

    각 셀의 상태:
      0 = UNKNOWN (데이터 없음)
      1 = OCCUPIED (포인트 존재 = 벽/장애물)
      2 = FREE (카메라가 지나감 = 통행 가능)
    """
    points = floor_data['points']
    cam_pos = floor_data['cam_positions']

    # 모든 포인트의 XY 범위 (마진 추가)
    all_xy = points[:, :2]
    if len(cam_pos) > 0:
        all_xy = np.vstack([all_xy, cam_pos[:, :2]])

    x_min, y_min = all_xy.min(axis=0) - 1.0
    x_max, y_max = all_xy.max(axis=0) + 1.0

    # 격자 크기
    width = int(np.ceil((x_max - x_min) / cell_size))
    height = int(np.ceil((y_max - y_min) / cell_size))
    grid = np.zeros((height, width), dtype=np.uint8)  # 0 = UNKNOWN

    # 포인트 → OCCUPIED (1)
    px = ((points[:, 0] - x_min) / cell_size).astype(int)
    py = ((points[:, 1] - y_min) / cell_size).astype(int)
    valid = (px >= 0) & (px < width) & (py >= 0) & (py < height)
    grid[py[valid], px[valid]] = 1

    # 카메라 궤적 → FREE (2)
    # 카메라 위치 주변 반경(~1m)도 통행 가능으로 마킹
    if len(cam_pos) > 0:
        cx = ((cam_pos[:, 0] - x_min) / cell_size).astype(int)
        cy = ((cam_pos[:, 1] - y_min) / cell_size).astype(int)
        radius = int(0.8 / cell_size)  # 카메라 주변 0.8m

        for i in range(len(cx)):
            if 0 <= cx[i] < width and 0 <= cy[i] < height:
                # 원형 영역을 FREE로 마킹
                for dy in range(-radius, radius + 1):
                    for dx in range(-radius, radius + 1):
                        if dx*dx + dy*dy <= radius*radius:
                            ny, nx = cy[i] + dy, cx[i] + dx
                            if 0 <= ny < height and 0 <= nx < width:
                                grid[ny, nx] = 2  # FREE가 OCCUPIED를 덮어씀

    return grid, x_min, y_min, width, height


# =============================================================================
# Step 4: 통행 가능 영역 정제
# =============================================================================

def extract_walkable_area(grid):
    """
    점유 격자에서 통행 가능 영역을 정제한다.

    1. FREE(2) 영역을 시드로 사용
    2. UNKNOWN(0) 중 FREE와 연결된 영역도 통행 가능으로 확장
    3. OCCUPIED(1)로 둘러싸인 UNKNOWN은 제외
    4. 모폴로지 연산(닫기)으로 작은 구멍 메우기
    """
    # FREE 영역 = 카메라가 지나간 곳
    walkable = (grid == 2).astype(np.uint8)

    # 팽창으로 카메라 주변 확장 (벽 가까이도 통행 가능)
    struct = np.ones((3, 3), dtype=bool)
    walkable = binary_dilation(walkable, structure=struct, iterations=2).astype(np.uint8)

    # OCCUPIED 영역은 통행 불가로 되돌리기
    walkable[grid == 1] = 0

    # 작은 구멍 메우기 (모폴로지 닫기)
    walkable = binary_dilation(walkable, iterations=1).astype(np.uint8)
    walkable = binary_erosion(walkable, iterations=1).astype(np.uint8)

    return walkable


# =============================================================================
# Step 5: 스켈레톤화 (Thinning)
# =============================================================================

def skeletonize(binary_image):
    """
    Zhang-Suen thinning 알고리즘으로 이진 이미지를 1픽셀 폭의
    스켈레톤(중심선)으로 축소한다.

    넓은 복도(10픽셀 폭)이든 좁은 복도(3픽셀 폭)이든
    중심선 1개만 남긴다.
    """
    img = binary_image.copy().astype(np.uint8)
    rows, cols = img.shape
    changed = True

    while changed:
        changed = False

        # Sub-iteration 1
        markers = np.zeros_like(img)
        for i in range(1, rows - 1):
            for j in range(1, cols - 1):
                if img[i, j] == 0:
                    continue
                # 8-이웃
                p2, p3, p4 = img[i-1, j], img[i-1, j+1], img[i, j+1]
                p5, p6, p7 = img[i+1, j+1], img[i+1, j], img[i+1, j-1]
                p8, p9 = img[i, j-1], img[i-1, j-1]
                neighbors = [p2, p3, p4, p5, p6, p7, p8, p9]

                # 조건 A: 이웃 수 2~6
                n_count = sum(neighbors)
                if not (2 <= n_count <= 6):
                    continue

                # 조건 B: 0→1 전환 수 = 1
                transitions = 0
                for k in range(8):
                    if neighbors[k] == 0 and neighbors[(k+1) % 8] == 1:
                        transitions += 1
                if transitions != 1:
                    continue

                # 조건 C & D (sub-iteration 1)
                if p2 * p4 * p6 != 0:
                    continue
                if p4 * p6 * p8 != 0:
                    continue

                markers[i, j] = 1

        img[markers == 1] = 0
        if markers.sum() > 0:
            changed = True

        # Sub-iteration 2
        markers = np.zeros_like(img)
        for i in range(1, rows - 1):
            for j in range(1, cols - 1):
                if img[i, j] == 0:
                    continue
                p2, p3, p4 = img[i-1, j], img[i-1, j+1], img[i, j+1]
                p5, p6, p7 = img[i+1, j+1], img[i+1, j], img[i+1, j-1]
                p8, p9 = img[i, j-1], img[i-1, j-1]
                neighbors = [p2, p3, p4, p5, p6, p7, p8, p9]

                n_count = sum(neighbors)
                if not (2 <= n_count <= 6):
                    continue

                transitions = 0
                for k in range(8):
                    if neighbors[k] == 0 and neighbors[(k+1) % 8] == 1:
                        transitions += 1
                if transitions != 1:
                    continue

                if p2 * p4 * p8 != 0:
                    continue
                if p2 * p6 * p8 != 0:
                    continue

                markers[i, j] = 1

        img[markers == 1] = 0
        if markers.sum() > 0:
            changed = True

    return img


# =============================================================================
# Step 6: 스켈레톤에서 그래프 추출
# =============================================================================

def skeleton_to_graph(skeleton, x_min, y_min, cell_size, z_height):
    """
    스켈레톤 이미지에서 노드와 엣지를 추출한다.

    - 분기점 (이웃 3개+) = JUNCTION
    - 끝점 (이웃 1개) = ENDPOINT
    - 분기점/끝점 사이의 경로 = 엣지
    """
    rows, cols = skeleton.shape
    nodes = []
    node_map = {}  # (y, x) → node_index

    # 각 스켈레톤 픽셀의 이웃 수 계산
    neighbor_count = np.zeros_like(skeleton, dtype=int)
    for i in range(1, rows - 1):
        for j in range(1, cols - 1):
            if skeleton[i, j] == 0:
                continue
            count = 0
            for di in [-1, 0, 1]:
                for dj in [-1, 0, 1]:
                    if di == 0 and dj == 0:
                        continue
                    if skeleton[i + di, j + dj] > 0:
                        count += 1
            neighbor_count[i, j] = count

    # 노드 추출: 끝점(이웃=1) 또는 분기점(이웃≥3)
    for i in range(1, rows - 1):
        for j in range(1, cols - 1):
            if skeleton[i, j] == 0:
                continue
            nc = neighbor_count[i, j]
            if nc == 1 or nc >= 3:
                # 격자 좌표 → 월드 좌표
                wx = x_min + j * cell_size
                wy = y_min + i * cell_size
                node_type = 'ENDPOINT' if nc == 1 else 'JUNCTION'
                node_idx = len(nodes)
                nodes.append({
                    'id': node_idx,
                    'x': float(wx),
                    'y': float(wy),
                    'z': float(z_height),
                    'type': node_type,
                    'grid_pos': (i, j),
                })
                node_map[(i, j)] = node_idx

    # 엣지 추출: 노드 사이의 스켈레톤 경로를 BFS로 추적
    edges = []
    visited_edges = set()

    for node in nodes:
        start = node['grid_pos']
        si, sj = start

        # 각 이웃 방향으로 경로 추적
        for di in [-1, 0, 1]:
            for dj in [-1, 0, 1]:
                if di == 0 and dj == 0:
                    continue
                ni, nj = si + di, sj + dj
                if not (0 <= ni < rows and 0 <= nj < cols):
                    continue
                if skeleton[ni, nj] == 0:
                    continue

                # BFS로 다음 노드까지 추적
                path_length = 0
                ci, cj = ni, nj
                prev_i, prev_j = si, sj
                found_node = None

                while True:
                    path_length += 1

                    # 다른 노드에 도달했는가?
                    if (ci, cj) in node_map and (ci, cj) != start:
                        found_node = node_map[(ci, cj)]
                        break

                    # 다음 픽셀 찾기 (이전 픽셀 제외)
                    next_found = False
                    for d2i in [-1, 0, 1]:
                        for d2j in [-1, 0, 1]:
                            if d2i == 0 and d2j == 0:
                                continue
                            n2i, n2j = ci + d2i, cj + d2j
                            if n2i == prev_i and n2j == prev_j:
                                continue
                            if 0 <= n2i < rows and 0 <= n2j < cols and skeleton[n2i, n2j] > 0:
                                prev_i, prev_j = ci, cj
                                ci, cj = n2i, n2j
                                next_found = True
                                break
                        if next_found:
                            break

                    if not next_found:
                        break

                    # 무한 루프 방지
                    if path_length > rows * cols:
                        break

                if found_node is not None:
                    edge_key = tuple(sorted([node['id'], found_node]))
                    if edge_key not in visited_edges:
                        visited_edges.add(edge_key)
                        distance = path_length * cell_size
                        edges.append({
                            'from': node['id'],
                            'to': found_node,
                            'distance': float(distance),
                        })

    return nodes, edges


# =============================================================================
# Step 7: 허브(로비) 감지 + 완전 그래프 생성
# =============================================================================

# 열린 공간 감지 임계값 (미터)
# 벽까지 거리가 이 값 이상인 영역을 "허브(로비)"로 판정
HUB_MIN_RADIUS = 2.0

# 허브 최소 면적 (제곱미터)
# 이 면적 미만인 열린 공간은 허브가 아닌 넓은 복도로 무시
HUB_MIN_AREA = 10.0

# 복도 진입점 탐지 시 허브 경계 확장 범위 (픽셀)
ENTRY_SEARCH_RADIUS = 3


def detect_hubs(walkable, cell_size=CELL_SIZE, grid=None):
    """
    통행 가능 영역에서 허브(로비, 홀 등 넓은 열린 공간)를 감지한다.

    [알고리즘]
    1. Distance Transform: 각 통행 가능 셀에서 가장 가까운 벽까지의 거리 계산
       - 복도: 거리 1~2m (좁음)
       - 로비: 거리 3~10m (넓음)

    2. 임계값 적용: 거리 > HUB_MIN_RADIUS인 셀 = 허브 후보

    3. 연결 영역 분석: 허브 후보 셀의 연결 영역(label)을 구함
       - 각 연결 영역 = 하나의 허브

    4. 면적 필터: HUB_MIN_AREA 이상인 영역만 허브로 확정

    Args:
        walkable: 이진 통행 가능 영역 (1=통행가능, 0=벽)
        cell_size: 격자 해상도 (미터/셀)
        grid: 원본 점유 격자 (OCCUPIED=1 셀 참조용, 없으면 walkable 반전 사용)

    Returns:
        hubs: 허브 정보 리스트
        dist: distance transform 맵
    """
    # Step 1: Distance Transform
    # 핵심: walkable 반전이 아니라 OCCUPIED(벽) 셀까지 거리를 측정해야 한다.
    # walkable 반전을 쓰면 UNKNOWN 영역 경계도 "벽"으로 취급되어
    # 로비 내부에서도 거리가 작게 나온다.
    if grid is not None:
        # 벽(OCCUPIED=1)이 아닌 곳 = True, 벽인 곳 = False
        not_wall = (grid != 1).astype(np.uint8)
        # 통행 가능 영역 내에서 가장 가까운 벽까지 거리
        dist = distance_transform_edt(not_wall) * cell_size
        # walkable 영역 밖은 0으로 마스킹
        dist[walkable == 0] = 0
    else:
        dist = distance_transform_edt(walkable) * cell_size

    # Step 2: 허브 후보 마스크
    hub_threshold_px = HUB_MIN_RADIUS  # 이미 미터 단위
    hub_mask = (dist >= hub_threshold_px).astype(np.uint8)

    # Step 3: 연결 영역 분석
    labeled, n_labels = label(hub_mask)

    # Step 4: 각 영역 분석 및 면적 필터
    hubs = []
    for label_id in range(1, n_labels + 1):
        region_mask = (labeled == label_id)
        area_cells = region_mask.sum()
        area_m2 = area_cells * cell_size * cell_size

        if area_m2 < HUB_MIN_AREA:
            continue

        # 중심 좌표 (무게중심)
        ys, xs = np.where(region_mask)
        center_y = int(np.mean(ys))
        center_x = int(np.mean(xs))

        # 최대 벽 거리
        max_radius = float(dist[region_mask].max())

        hubs.append({
            'mask': region_mask,
            'area_m2': area_m2,
            'center': (center_y, center_x),
            'max_radius': max_radius,
            'label_id': label_id,
        })

    return hubs, dist


def find_corridor_entries(hub, walkable, skeleton, cell_size=CELL_SIZE):
    """
    허브 경계에서 복도가 진입하는 지점을 찾는다.

    [알고리즘]
    1. 허브 마스크를 약간 팽창시켜 경계 영역 생성
    2. 경계 영역 내에서 스켈레톤 픽셀을 찾음
       = 복도 중심선이 허브와 만나는 지점 = 진입점
    3. 가까운 진입점끼리 병합 (같은 복도의 중복 감지 방지)

    Args:
        hub: 허브 정보 딕셔너리 (detect_hubs의 출력)
        walkable: 통행 가능 영역
        skeleton: 스켈레톤 이미지
        cell_size: 격자 해상도

    Returns:
        entries: 진입점 리스트
            - 'grid_pos': (y, x) 격자 좌표
            - 'direction': 진입 방향 벡터 (허브 중심 → 진입점)
    """
    hub_mask = hub['mask']
    center_y, center_x = hub['center']

    # Step 1: 허브 경계 영역 = 팽창 - 원본
    # 허브 바로 바깥 테두리에서 스켈레톤을 찾기 위함
    dilated = binary_dilation(hub_mask, iterations=ENTRY_SEARCH_RADIUS).astype(np.uint8)
    border = dilated.astype(int) - hub_mask.astype(int)
    border = np.clip(border, 0, 1).astype(np.uint8)

    # Step 2: 경계에서 스켈레톤과 겹치는 픽셀 = 진입점 후보
    entry_mask = (border > 0) & (skeleton > 0)
    entry_positions = list(zip(*np.where(entry_mask)))

    if not entry_positions:
        # 스켈레톤이 허브 내부를 통과하는 경우:
        # 허브 내부에서 스켈레톤 끝점/분기점을 진입점으로 사용
        inner_skel = (hub_mask > 0) & (skeleton > 0)
        entry_positions = list(zip(*np.where(inner_skel)))

    if not entry_positions:
        return []

    # Step 3: 가까운 진입점 병합 (같은 복도가 여러 픽셀에서 감지되는 것 방지)
    merge_radius = int(1.5 / cell_size)  # 1.5m 이내는 같은 진입점
    merged = _merge_nearby_points(entry_positions, merge_radius)

    # 진입 방향 계산: 허브 중심 → 진입점 벡터
    entries = []
    for y, x in merged:
        dy = y - center_y
        dx = x - center_x
        length = np.sqrt(dy*dy + dx*dx)
        if length > 0:
            direction = (dy / length, dx / length)
        else:
            direction = (0, 0)

        entries.append({
            'grid_pos': (y, x),
            'direction': direction,
        })

    return entries


def _merge_nearby_points(points, radius):
    """
    반경 내의 가까운 점들을 하나로 병합한다 (무게중심 사용).

    Args:
        points: (y, x) 좌표 리스트
        radius: 병합 반경 (픽셀)

    Returns:
        병합된 좌표 리스트
    """
    if not points:
        return []

    points = list(points)
    merged = []
    used = set()

    for i, (y1, x1) in enumerate(points):
        if i in used:
            continue

        # i와 가까운 점들 수집
        group_y = [y1]
        group_x = [x1]
        used.add(i)

        for j, (y2, x2) in enumerate(points):
            if j in used:
                continue
            if abs(y1 - y2) <= radius and abs(x1 - x2) <= radius:
                dist = np.sqrt((y1 - y2)**2 + (x1 - x2)**2)
                if dist <= radius:
                    group_y.append(y2)
                    group_x.append(x2)
                    used.add(j)

        # 그룹의 무게중심
        merged.append((int(np.mean(group_y)), int(np.mean(group_x))))

    return merged


def create_hub_edges(hubs, entries_per_hub, existing_nodes, existing_edges,
                     x_min, y_min, cell_size, z_height):
    """
    각 허브에 대해 진입점들 사이의 완전 그래프 엣지를 생성한다.

    [핵심 로직]
    허브에 N개 복도가 연결되어 있으면, N×(N-1)/2개의 엣지를 생성한다.
    각 엣지의 거리 = 두 진입점 간 유클리드 거리 (로비 내부는 직선 이동 가능).

    예: 허브에 3개 복도(A, B, C) 연결
      → 엣지: A↔B, A↔C, B↔C (3개)
      → 길찾기 시 A→C를 직접 이동 가능 (A→중심→C 불필요)

    [기존 노드 재활용]
    진입점과 가장 가까운 기존 노드를 찾아서 연결한다.
    새 노드를 만들지 않고 기존 그래프 위에 엣지만 추가한다.

    Args:
        hubs: 허브 리스트
        entries_per_hub: 허브별 진입점 리스트
        existing_nodes: 기존 그래프 노드
        existing_edges: 기존 그래프 엣지 (수정됨)
        x_min, y_min: 격자 원점
        cell_size: 격자 해상도
        z_height: 이 층의 Z 높이

    Returns:
        new_nodes: 새로 생성된 노드 (기존에 가까운 노드가 없을 때)
        new_edges: 새로 생성된 허브 엣지
        hub_info: 시각화용 허브 정보
    """
    new_nodes = []
    new_edges = []
    hub_info = []  # 시각화용

    # 기존 노드의 격자 좌표 목록 (가까운 노드 탐색용)
    node_grid_positions = []
    for n in existing_nodes:
        if 'grid_pos' in n:
            node_grid_positions.append(n['grid_pos'])
        else:
            # 월드 좌표 → 격자 좌표 역변환
            gx = int((n['x'] - x_min) / cell_size)
            gy = int((n['y'] - y_min) / cell_size)
            node_grid_positions.append((gy, gx))

    max_existing_id = max((n['id'] for n in existing_nodes), default=-1)
    next_id = max_existing_id + 1

    for hub_idx, (hub, entries) in enumerate(zip(hubs, entries_per_hub)):
        if len(entries) < 2:
            continue

        center_y, center_x = hub['center']
        center_wx = x_min + center_x * cell_size
        center_wy = y_min + center_y * cell_size

        # 각 진입점을 기존 노드에 매핑하거나 새 노드 생성
        entry_node_ids = []
        for entry in entries:
            ey, ex = entry['grid_pos']

            # 가장 가까운 기존 노드 찾기 (5픽셀 = 0.75m 이내)
            best_dist = float('inf')
            best_node_id = None
            search_radius = int(2.0 / cell_size)

            for node in existing_nodes:
                if 'grid_pos' not in node:
                    continue
                ny, nx = node['grid_pos']
                dist = np.sqrt((ey - ny)**2 + (ex - nx)**2)
                if dist < best_dist and dist <= search_radius:
                    best_dist = dist
                    best_node_id = node['id']

            if best_node_id is not None:
                entry_node_ids.append(best_node_id)
            else:
                # 가까운 노드 없음 → 새 HUB_ENTRY 노드 생성
                wx = x_min + ex * cell_size
                wy = y_min + ey * cell_size
                new_node = {
                    'id': next_id,
                    'x': float(wx),
                    'y': float(wy),
                    'z': float(z_height),
                    'type': 'HUB_ENTRY',
                    'grid_pos': (ey, ex),
                }
                new_nodes.append(new_node)
                existing_nodes.append(new_node)  # 이후 탐색에서도 사용
                entry_node_ids.append(next_id)
                next_id += 1

        # 완전 그래프 엣지 생성: 모든 진입점 쌍을 연결
        # N개 진입점 → N×(N-1)/2 엣지
        node_by_id = {n['id']: n for n in existing_nodes + new_nodes}
        hub_edge_count = 0

        for i in range(len(entry_node_ids)):
            for j in range(i + 1, len(entry_node_ids)):
                nid_a = entry_node_ids[i]
                nid_b = entry_node_ids[j]

                # 같은 노드면 스킵
                if nid_a == nid_b:
                    continue

                # 이미 엣지가 있으면 스킵
                edge_key = tuple(sorted([nid_a, nid_b]))
                already_exists = any(
                    tuple(sorted([e['from'], e['to']])) == edge_key
                    for e in existing_edges + new_edges
                )
                if already_exists:
                    continue

                # 거리 = 두 노드 간 유클리드 거리
                na = node_by_id[nid_a]
                nb = node_by_id[nid_b]
                dist = np.sqrt(
                    (na['x'] - nb['x'])**2 +
                    (na['y'] - nb['y'])**2
                )

                new_edges.append({
                    'from': nid_a,
                    'to': nid_b,
                    'distance': float(dist),
                    'type': 'HUB',  # 허브 내부 엣지 표시
                })
                hub_edge_count += 1

        hub_info.append({
            'center': (center_wx, center_wy, z_height),
            'area_m2': hub['area_m2'],
            'max_radius': hub['max_radius'],
            'n_entries': len(entries),
            'n_edges': hub_edge_count,
            'entry_positions': [(x_min + e['grid_pos'][1] * cell_size,
                                y_min + e['grid_pos'][0] * cell_size)
                               for e in entries],
        })

    return new_nodes, new_edges, hub_info


# =============================================================================
# 시각화
# =============================================================================

def visualize_all(floors_data, peaks, all_nodes, all_edges, world_points, cam_positions):
    """결과 이미지 4장 생성"""

    # 이미지 1: 3D 포인트 클라우드
    fig = plt.figure(figsize=(14, 10))
    ax = fig.add_subplot(111, projection='3d')

    # 포인트 서브샘플 (너무 많으면 느림)
    if len(world_points) > 5000:
        idx = np.random.choice(len(world_points), 5000, replace=False)
        pts = world_points[idx]
    else:
        pts = world_points

    ax.scatter(pts[:, 0], pts[:, 1], pts[:, 2],
               c=pts[:, 2], cmap='viridis', s=1, alpha=0.3)
    ax.plot(cam_positions[:, 0], cam_positions[:, 1], cam_positions[:, 2],
            color='red', linewidth=1, alpha=0.8, label='Camera trajectory')
    ax.set_xlabel('X (m)')
    ax.set_ylabel('Y (m)')
    ax.set_zlabel('Z (m)')
    ax.set_title(f'3D Point Cloud ({len(world_points)} points)', fontweight='bold')
    ax.legend()
    ax.view_init(elev=25, azim=-60)
    p1 = os.path.join(OUTPUT_DIR, '1_pointcloud_3d.png')
    fig.savefig(p1, dpi=150, bbox_inches='tight', facecolor='white')
    plt.close(fig)
    print(f"  saved: {p1}")

    # 이미지 2~3: 층별 점유격자 + 스켈레톤 + 그래프
    n_floors = len(floors_data)
    fig, axes = plt.subplots(n_floors, 3, figsize=(18, 6 * n_floors))
    if n_floors == 1:
        axes = axes[np.newaxis, :]

    for idx, (fi, fdata) in enumerate(sorted(floors_data.items())):
        grid = fdata['grid']
        walkable = fdata['walkable']
        skel = fdata['skeleton']
        fnodes = fdata['nodes']
        fedges = fdata['edges']

        # 점유 격자
        ax = axes[idx, 0]
        display = np.zeros((*grid.shape, 3))
        display[grid == 0] = [0.9, 0.9, 0.9]  # UNKNOWN = 밝은 회색
        display[grid == 1] = [0.2, 0.2, 0.2]  # OCCUPIED = 어두운 회색
        display[grid == 2] = [0.6, 0.85, 1.0]  # FREE = 하늘색
        ax.imshow(display, origin='lower')
        ax.set_title(f'{fi+1}F Occupancy Grid', fontweight='bold')
        ax.set_xlabel('X')
        ax.set_ylabel('Y')

        # 통행가능 + 스켈레톤
        ax = axes[idx, 1]
        display2 = np.zeros((*walkable.shape, 3))
        display2[walkable == 1] = [0.85, 0.95, 0.85]  # 통행가능 = 연녹색
        display2[skel == 1] = [1.0, 0.3, 0.0]  # 스켈레톤 = 빨간색
        ax.imshow(display2, origin='lower')
        ax.set_title(f'{fi+1}F Skeleton', fontweight='bold')

        # 그래프
        ax = axes[idx, 2]
        ax.imshow(display, origin='lower', alpha=0.3)

        # 허브 영역 표시 (반투명 빨간색)
        hub_list = fdata.get('hubs', [])
        for hub in hub_list:
            hub_overlay = np.zeros((*grid.shape, 4))
            hub_overlay[hub['mask']] = [1.0, 0.3, 0.3, 0.25]
            ax.imshow(hub_overlay, origin='lower')

        # 엣지 그리기
        node_by_id = {n['id']: n for n in fnodes}
        for e in fedges:
            n1 = node_by_id.get(e['from'])
            n2 = node_by_id.get(e['to'])
            if not n1 or not n2 or 'grid_pos' not in n1 or 'grid_pos' not in n2:
                continue
            # 허브 엣지는 녹색 점선으로 구분
            if e.get('type') == 'HUB':
                ax.plot([n1['grid_pos'][1], n2['grid_pos'][1]],
                        [n1['grid_pos'][0], n2['grid_pos'][0]],
                        color='#4CAF50', linewidth=2, alpha=0.9, linestyle='--')
            else:
                ax.plot([n1['grid_pos'][1], n2['grid_pos'][1]],
                        [n1['grid_pos'][0], n2['grid_pos'][0]],
                        color='#42A5F5', linewidth=1.5, alpha=0.8)

        # 노드 그리기
        for n in fnodes:
            if 'grid_pos' not in n:
                continue
            if n['type'] == 'HUB_ENTRY':
                color = '#4CAF50'
                size = 50
            elif n['type'] == 'ENDPOINT':
                color = 'red'
                size = 40
            else:
                color = 'orange'
                size = 25
            ax.scatter(n['grid_pos'][1], n['grid_pos'][0],
                       c=color, s=size, zorder=5, edgecolors='black', linewidths=0.5)

        n_hub_edges = sum(1 for e in fedges if e.get('type') == 'HUB')
        hub_label = f' +{n_hub_edges}hub' if n_hub_edges > 0 else ''
        ax.set_title(f'{fi+1}F Graph (N={len(fnodes)}, E={len(fedges)}{hub_label})',
                     fontweight='bold')

    plt.tight_layout()
    p2 = os.path.join(OUTPUT_DIR, '2_floor_analysis.png')
    fig.savefig(p2, dpi=150, bbox_inches='tight', facecolor='white')
    plt.close(fig)
    print(f"  saved: {p2}")

    # 이미지 3: 전체 3D 그래프
    fig = plt.figure(figsize=(14, 10))
    ax = fig.add_subplot(111, projection='3d')

    node_map_all = {(n['x'], n['y'], n['z']): n for n in all_nodes}
    node_by_id = {n['id']: n for n in all_nodes}

    for n in all_nodes:
        if n['type'] == 'HUB_ENTRY':
            color, size = '#4CAF50', 60
        elif n['type'] == 'ENDPOINT':
            color, size = 'red', 50
        else:
            color, size = 'orange', 20
        ax.scatter(n['x'], n['y'], n['z'], c=color, s=size, zorder=5)

    for e in all_edges:
        n1 = node_by_id.get(e['from'])
        n2 = node_by_id.get(e['to'])
        if n1 and n2:
            if e.get('type') == 'HUB':
                ax.plot([n1['x'], n2['x']], [n1['y'], n2['y']], [n1['z'], n2['z']],
                        color='#4CAF50', linewidth=2, alpha=0.8, linestyle='--')
            else:
                ax.plot([n1['x'], n2['x']], [n1['y'], n2['y']], [n1['z'], n2['z']],
                        color='#42A5F5', linewidth=1, alpha=0.7)

    ax.set_xlabel('X (m)')
    ax.set_ylabel('Y (m)')
    ax.set_zlabel('Z (m)')
    ax.set_title(f'3D Graph (N={len(all_nodes)}, E={len(all_edges)})', fontweight='bold')
    ax.view_init(elev=25, azim=-60)
    p3 = os.path.join(OUTPUT_DIR, '3_graph_3d.png')
    fig.savefig(p3, dpi=150, bbox_inches='tight', facecolor='white')
    plt.close(fig)
    print(f"  saved: {p3}")

    return [p1, p2, p3]


# =============================================================================
# 메인
# =============================================================================

def main():
    print("=" * 60)
    print("  포인트 클라우드 기반 파이프라인")
    print("=" * 60)

    # Step 1
    print("\n[1/6] 포인트 클라우드 추출...")
    world_points, cam_positions = extract_pointcloud(DB_PATH)

    # Step 2
    print("\n[2/6] 층 분리...")
    floors, peaks = separate_floors_by_z(world_points, cam_positions)

    # Step 3~6: 층별 처리
    all_nodes = []
    all_edges = []
    node_id_offset = 0

    for fi, fdata in sorted(floors.items()):
        print(f"\n--- {fi+1}층 처리 ---")

        # Step 3: 점유 격자
        print("[3/6] 점유 격자 생성...")
        grid, x_min, y_min, w, h = create_occupancy_grid(fdata, CELL_SIZE)
        print(f"  격자 크기: {w}x{h} ({w*CELL_SIZE:.0f}m x {h*CELL_SIZE:.0f}m)")
        occupied = (grid == 1).sum()
        free = (grid == 2).sum()
        print(f"  OCCUPIED: {occupied}셀, FREE: {free}셀")

        # Step 4: 통행 가능 영역
        print("[4/6] 통행 가능 영역 추출...")
        walkable = extract_walkable_area(grid)
        print(f"  통행 가능: {walkable.sum()}셀")

        # Step 5: 스켈레톤화
        print("[5/6] 스켈레톤화...")
        skel = skeletonize(walkable)
        print(f"  스켈레톤 픽셀: {skel.sum()}개")

        # Step 6: 그래프 추출
        print("[6/7] 그래프 추출...")
        nodes, edges = skeleton_to_graph(skel, x_min, y_min, CELL_SIZE, peaks[fi])

        # ID 오프셋 적용 (다층 병합용)
        for n in nodes:
            n['id'] += node_id_offset
        for e in edges:
            e['from'] += node_id_offset
            e['to'] += node_id_offset

        print(f"  노드: {len(nodes)}개, 엣지: {len(edges)}개")

        # Step 7: 허브(로비) 감지 + 완전 그래프
        print("[7/7] 허브 감지...")
        hubs, dist_map = detect_hubs(walkable, CELL_SIZE, grid=grid)

        if hubs:
            # 각 허브의 복도 진입점 탐지
            entries_per_hub = []
            for hub in hubs:
                entries = find_corridor_entries(hub, walkable, skel, CELL_SIZE)
                entries_per_hub.append(entries)

            # 허브 엣지 생성
            hub_nodes, hub_edges, hub_info = create_hub_edges(
                hubs, entries_per_hub, nodes, edges,
                x_min, y_min, CELL_SIZE, peaks[fi]
            )

            nodes.extend(hub_nodes)
            edges.extend(hub_edges)

            for hi in hub_info:
                print(f"  허브: {hi['area_m2']:.1f}m², "
                      f"반경 {hi['max_radius']:.1f}m, "
                      f"진입점 {hi['n_entries']}개 → "
                      f"엣지 {hi['n_edges']}개 추가")
        else:
            hub_info = []
            entries_per_hub = []
            print("  허브 없음")

        # 데이터 저장
        fdata['grid'] = grid
        fdata['walkable'] = walkable
        fdata['skeleton'] = skel
        fdata['dist_map'] = dist_map
        fdata['nodes'] = nodes
        fdata['edges'] = edges
        fdata['hubs'] = hubs
        fdata['hub_info'] = hub_info
        fdata['entries_per_hub'] = entries_per_hub
        fdata['grid_origin'] = (x_min, y_min)

        print(f"  최종: 노드 {len(nodes)}개, 엣지 {len(edges)}개")

        all_nodes.extend(nodes)
        all_edges.extend(edges)
        node_id_offset += len(nodes)

    print(f"\n{'='*60}")
    print(f"  전체 그래프: 노드 {len(all_nodes)}개, 엣지 {len(all_edges)}개")
    print(f"{'='*60}")

    # 시각화
    print("\n  시각화 생성 중...")
    visualize_all(floors, peaks, all_nodes, all_edges, world_points, cam_positions)

    print(f"\n  Done! Output: {OUTPUT_DIR}")


if __name__ == '__main__':
    main()
