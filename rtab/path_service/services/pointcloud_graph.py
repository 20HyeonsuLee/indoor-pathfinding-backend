"""
포인트클라우드 기반 그래프 생성 모듈

포인트클라우드 → 2D 점유격자 → 스켈레톤 → 그래프를 생성하고,
path_service의 노드/엣지 포맷으로 변환한다.
"""
import uuid

import sqlite3
import struct
import subprocess
import tempfile
import os
import numpy as np
from scipy.ndimage import gaussian_filter1d, binary_dilation, binary_erosion, label
from scipy.ndimage import distance_transform_edt
from collections import deque


CELL_SIZE = 0.3   # 점유 격자 해상도 (미터/셀) — 크게 할수록 노드 줄어듦
VOXEL_SIZE = 0.05  # rtabmap-export 복셀 크기 (미터)
VISUALIZATION_VOXEL_SIZE = 0.2  # 시각화용 복셀 크기 (웹 전송용, 경량화)


# =============================================================================
# Step 1: rtabmap-export CLI로 밀집 포인트 클라우드 추출
# =============================================================================

def _read_ply_binary(ply_path):
    """
    rtabmap-export가 생성한 binary PLY 파일에서 XYZ 좌표를 읽는다.

    포맷: x,y,z (float32) + nx,ny,nz (float32) + r,g,b (uint8) + curvature (float32)
    = 31 bytes per point
    """
    with open(ply_path, 'rb') as f:
        # 헤더 파싱
        n_vertices = 0
        while True:
            line = f.readline().decode('ascii', errors='ignore').strip()
            if line.startswith('element vertex'):
                n_vertices = int(line.split()[-1])
            if line == 'end_header':
                break

        if n_vertices == 0:
            return np.empty((0, 3))

        # 바이너리 데이터: 포인트당 31바이트 (xyz + normal + rgb + curvature)
        point_dtype = np.dtype([
            ('x', '<f4'), ('y', '<f4'), ('z', '<f4'),
            ('nx', '<f4'), ('ny', '<f4'), ('nz', '<f4'),
            ('r', 'u1'), ('g', 'u1'), ('b', 'u1'),
            ('curvature', '<f4'),
        ])
        data = np.frombuffer(f.read(n_vertices * point_dtype.itemsize),
                             dtype=point_dtype, count=n_vertices)

    points = np.column_stack([data['x'], data['y'], data['z']])
    return points


def extract_visualization_ply(db_path, output_path, voxel_size=VISUALIZATION_VOXEL_SIZE):
    """
    웹 시각화용 경량 PLY 파일을 추출한다.

    xyz + rgb만 포함하여 파일 크기를 최소화한다.
    (법선, 곡률 제거 → 31 → 15 bytes/point)
    """
    with tempfile.TemporaryDirectory() as tmpdir:
        cmd = [
            'rtabmap-export',
            '--cloud',
            '--voxel', str(voxel_size),
            '--max_range', '5',
            '--depth_confidence', '50',
            '--decimation', '4',
            '--output_dir', tmpdir,
            '--output', 'cloud',
            db_path,
        ]
        print(f"  rtabmap-export 실행: {' '.join(cmd)}")
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
        print(f"  returncode={result.returncode}")
        if result.stdout: print(f"  stdout: {result.stdout[:500]}")
        if result.stderr: print(f"  stderr: {result.stderr[:500]}")

        if result.returncode != 0:
            raise RuntimeError(f"rtabmap-export 실패 (code={result.returncode}):\n{result.stderr[:500]}")

        # 출력 파일 찾기 (버전에 따라 파일명이 다를 수 있음)
        tmpfiles = os.listdir(tmpdir)
        print(f"  tmpdir 파일: {tmpfiles}")

        source_ply = None
        for f in tmpfiles:
            if f.endswith('.ply'):
                source_ply = os.path.join(tmpdir, f)
                break

        if source_ply is None:
            raise FileNotFoundError(f"PLY 파일이 생성되지 않았습니다. tmpdir 내용: {tmpfiles}")

        _convert_ply_xyz_rgb(source_ply, output_path)

    file_size = os.path.getsize(output_path)
    print(f"  시각화 PLY 생성: {output_path}")
    print(f"  파일 크기: {file_size / (1024 * 1024):.2f} MB")
    return output_path


def _convert_ply_xyz_rgb(source_ply, output_path):
    """
    rtabmap-export PLY(31 bytes/point)에서 xyz+rgb(15 bytes/point)만 추출하여 저장한다.
    """
    with open(source_ply, 'rb') as f:
        n_vertices = 0
        while True:
            line = f.readline().decode('ascii', errors='ignore').strip()
            if line.startswith('element vertex'):
                n_vertices = int(line.split()[-1])
            if line == 'end_header':
                break

        if n_vertices == 0:
            raise ValueError("포인트클라우드에 정점이 없습니다.")

        source_dtype = np.dtype([
            ('x', '<f4'), ('y', '<f4'), ('z', '<f4'),
            ('nx', '<f4'), ('ny', '<f4'), ('nz', '<f4'),
            ('r', 'u1'), ('g', 'u1'), ('b', 'u1'),
            ('curvature', '<f4'),
        ])
        data = np.frombuffer(f.read(n_vertices * source_dtype.itemsize),
                             dtype=source_dtype, count=n_vertices)

    header = (
        "ply\n"
        "format binary_little_endian 1.0\n"
        f"element vertex {n_vertices}\n"
        "property float x\n"
        "property float y\n"
        "property float z\n"
        "property uchar red\n"
        "property uchar green\n"
        "property uchar blue\n"
        "end_header\n"
    )

    output_dtype = np.dtype([
        ('x', '<f4'), ('y', '<f4'), ('z', '<f4'),
        ('r', 'u1'), ('g', 'u1'), ('b', 'u1'),
    ])
    output_data = np.empty(n_vertices, dtype=output_dtype)
    output_data['x'] = data['x']
    output_data['y'] = data['y']
    output_data['z'] = data['z']
    output_data['r'] = data['r']
    output_data['g'] = data['g']
    output_data['b'] = data['b']

    with open(output_path, 'wb') as f:
        f.write(header.encode('ascii'))
        f.write(output_data.tobytes())

    print(f"  PLY 변환: {n_vertices:,}개 포인트, "
          f"{n_vertices * 31 / 1024 / 1024:.1f}MB → {n_vertices * 15 / 1024 / 1024:.1f}MB")


def extract_pointcloud(db_path, voxel_size=VOXEL_SIZE):
    """
    rtabmap-export CLI로 DB에서 밀집 포인트 클라우드를 추출한다.

    rtabmap-export가 SLAM 최적화, depth 역투영, confidence 필터,
    복셀 다운샘플링을 모두 처리하므로 직접 파싱할 필요가 없다.

    카메라 궤적은 DB에서 직접 읽는다.
    """
    # ── rtabmap-export로 PLY 생성 ──
    with tempfile.TemporaryDirectory() as tmpdir:
        cmd = [
            'rtabmap-export',
            '--cloud',
            '--voxel', str(voxel_size),
            '--max_range', '5',
            '--depth_confidence', '50',
            '--decimation', '2',
            '--output_dir', tmpdir,
            '--output', 'cloud',
            db_path,
        ]
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
        if result.returncode != 0:
            raise RuntimeError(f"rtabmap-export 실패:\n{result.stderr}")

        ply_path = os.path.join(tmpdir, 'cloud_cloud.ply')
        world_points = _read_ply_binary(ply_path)

    # ── 카메라 궤적은 DB에서 직접 추출 ──
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()
    cursor.execute('SELECT id, pose FROM Node ORDER BY id')

    cam_positions = []
    for node_id, pose_blob in cursor.fetchall():
        if pose_blob and len(pose_blob) == 48:
            matrix = np.array(struct.unpack('12f', pose_blob)).reshape(3, 4)
            if np.isfinite(matrix).all() and not np.allclose(matrix, 0):
                cam_positions.append(matrix[:, 3])

    conn.close()
    cam_positions = np.array(cam_positions)

    print(f"  포인트 클라우드: {len(world_points):,}개 (복셀 {voxel_size}m)")
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
    카메라 궤적 Z 기반으로 층을 분리하고, 포인트클라우드를 층별 Z 범위로 슬라이싱한다.

    [계단 처리]
    vertical_detector로 계단/엘리베이터 구간을 먼저 감지하고,
    해당 구간의 카메라 궤적을 층 피크 탐지에서 제외한다.
    계단 구간의 포인트클라우드도 층 할당에서 제외된다.
    """
    from services.vertical_detector import detect_stairs_first

    # ─── 계단 구간 감지 및 제외 ───
    passages, stair_mask = detect_stairs_first(cam_positions)
    floor_cam_mask = ~stair_mask  # 계단이 아닌 카메라 궤적

    if passages:
        for p in passages:
            print(f"  {p['type']}: z={p['z_start']:.1f}→{p['z_end']:.1f}m "
                  f"({p['direction']}, idx {p['start_idx']}~{p['end_idx']})")

    # 계단 제외된 카메라 궤적으로 층 피크 탐지
    cam_z_floor = cam_positions[floor_cam_mask, 2] if floor_cam_mask.sum() > 0 else cam_positions[:, 2]
    cam_z = cam_positions[:, 2]

    # 계단 제외된 궤적으로 피크 탐지
    z_min, z_max = cam_z_floor.min(), cam_z_floor.max()

    bin_size = 0.3
    num_bins = max(int((z_max - z_min) / bin_size), 10)
    hist, bin_edges = np.histogram(cam_z_floor, bins=num_bins)
    bin_centers = (bin_edges[:-1] + bin_edges[1:]) / 2
    smoothed = gaussian_filter1d(hist.astype(float), sigma=0.8)

    min_count = max(len(cam_z_floor) * 0.03, 3)
    min_peak_dist = floor_height * 0.7

    significant = smoothed >= min_count
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

    peaks = []
    for gs, ge in groups:
        region_centers = bin_centers[gs:ge]
        region_weights = smoothed[gs:ge]
        if region_weights.sum() < min_count:
            continue
        peak_z = float(np.average(region_centers, weights=region_weights))
        if all(abs(peak_z - p) >= min_peak_dist for p in peaks):
            peaks.append(peak_z)

    if not peaks:
        peaks = [float(np.median(cam_z))]

    peaks.sort()
    print(f"  카메라 Z 피크: {[f'{p:.1f}m' for p in peaks]}")

    # ─── 층별 Z 경계 산출 ───
    # 인접 피크의 중간점이 경계
    z_boundaries = []
    for i in range(len(peaks) - 1):
        z_boundaries.append((peaks[i] + peaks[i + 1]) / 2)

    # ─── 카메라 궤적 층 할당 (계단 궤적 제외) ───
    peaks_arr = np.array(peaks)
    cam_floors = np.argmin(np.abs(cam_z[:, None] - peaks_arr[None, :]), axis=1)

    z_margin = floor_height * 0.6
    point_z = points[:, 2]

    floors = {}
    for fi in range(len(peaks)):
        if len(peaks) == 1:
            z_lo = peaks[fi] - z_margin
            z_hi = peaks[fi] + z_margin
        else:
            z_lo = z_boundaries[fi - 1] if fi > 0 else peaks[fi] - z_margin
            z_hi = z_boundaries[fi] if fi < len(peaks) - 1 else peaks[fi] + z_margin

        point_mask = (point_z >= z_lo) & (point_z <= z_hi)
        # 계단이 아닌 카메라만 이 층에 포함
        cam_mask = (cam_floors == fi) & floor_cam_mask

        if point_mask.sum() < 50:
            continue

        floors[fi] = {
            'points': points[point_mask],
            'cam_positions': cam_positions[cam_mask] if cam_mask.sum() > 0 else np.empty((0, 3)),
            'z_peak': peaks[fi],
            'z_range': (z_lo, z_hi),
            'point_count': int(point_mask.sum()),
            'cam_count': int(cam_mask.sum()),
        }
        print(f"  {fi+1}층 (z={peaks[fi]:.1f}m, range={z_lo:.1f}~{z_hi:.1f}): "
              f"포인트 {point_mask.sum()}개, 궤적 {cam_mask.sum()}개 "
              f"(계단 제외 {stair_mask.sum()}개)")

    return floors, peaks


# =============================================================================
# Step 3: 2D 점유 격자 생성
# =============================================================================

# ── 바닥 슬라이싱 높이 경계 (바닥 기준, 미터) ──
GROUND_Z_MIN = 0.05  # 바닥 표면 노이즈 제외
GROUND_Z_MAX = 0.30  # 바닥 표면 상한

# 셀당 이 개수 이상의 포인트가 있어야 바닥으로 인정
GROUND_DENSITY_THRESHOLD = 3


def _project_to_grid_with_density(points_2d, x_min, y_min, cell_size,
                                  width, height, min_count):
    """
    포인트를 격자에 투영하되, 셀당 min_count 이상일 때만 점유로 판정한다.
    """
    occupied = np.zeros((height, width), dtype=np.uint8)
    if len(points_2d) == 0:
        return occupied
    px = ((points_2d[:, 0] - x_min) / cell_size).astype(int)
    py = ((points_2d[:, 1] - y_min) / cell_size).astype(int)
    valid = (px >= 0) & (px < width) & (py >= 0) & (py < height)

    count_grid = np.zeros((height, width), dtype=int)
    np.add.at(count_grid, (py[valid], px[valid]), 1)
    occupied[count_grid >= min_count] = 1
    return occupied


def create_occupancy_grid(floor_data, cell_size=CELL_SIZE):
    """
    바닥 레이어(Ground)만으로 2D 점유 격자를 생성한다.

    [핵심 아이디어]
    Ground 레이어(0.05~0.30m)의 포인트가 찍힌 곳 = 바닥이 존재하는 곳.
    바닥이 있는 곳이 통행 가능 영역이고, 바닥이 없는 곳이 벽/경계이다.

    기존의 3레이어 합집합(Union) 방식은 바닥 포인트를 "장애물"로 취급하여
    Union/Walkable이 엉망이 되는 문제가 있었다.

    각 셀의 상태:
      0 = UNKNOWN (데이터 없음 = 벽 또는 미스캔)
      1 = FLOOR (바닥 포인트 존재 = 통행 가능)
    """
    points = floor_data['points']
    cam_pos = floor_data['cam_positions']
    z_peak = floor_data.get('z_peak', np.median(points[:, 2]))

    cam_height = 1.2
    floor_z = z_peak - cam_height

    # ── XY 범위 ──
    all_xy = points[:, :2]
    if len(cam_pos) > 0:
        all_xy = np.vstack([all_xy, cam_pos[:, :2]])

    x_min, y_min = all_xy.min(axis=0) - 1.0
    x_max, y_max = all_xy.max(axis=0) + 1.0

    width = int(np.ceil((x_max - x_min) / cell_size))
    height = int(np.ceil((y_max - y_min) / cell_size))

    # ── 바닥 레이어 추출 ──
    ground_mask = (
        (points[:, 2] >= floor_z + GROUND_Z_MIN) &
        (points[:, 2] <= floor_z + GROUND_Z_MAX)
    )
    floor_grid = _project_to_grid_with_density(
        points[ground_mask], x_min, y_min, cell_size,
        width, height, GROUND_DENSITY_THRESHOLD,
    )

    print(f"  바닥 셀: {floor_grid.sum()}, floor_z={floor_z:.2f}m")

    return floor_grid, x_min, y_min, width, height


# =============================================================================
# Step 4: 통행 가능 영역 정제
# =============================================================================

RIDGE_MIN_DIST = 0.3   # 능선 최소 벽 거리 (미터)
RIDGE_MAX_DIST = 10.0  # 능선 최대 벽 거리 (미터) — 건물 밖 제외


def extract_ridge(floor_grid, cam_positions=None, x_min=0, y_min=0,
                  cell_size=CELL_SIZE):
    """
    Distance Transform의 능선(ridge)을 추출하여 복도 중심선을 구한다.

    [핵심]
    벽까지 거리의 국소 최댓값 = 양쪽 벽에서 가장 먼 지점 = 복도 한가운데.
    넓은 복도든 좁은 복도든 중심선 1줄만 나온다.
    walkable → skeleton 과정이 불필요.

    [알고리즘]
    1. Distance Transform: 각 셀에서 벽까지 거리
    2. 국소 최댓값(ridge) 추출: 4방향 중 하나라도 양쪽보다 크면 능선
    3. 카메라 궤적 근처 연결 영역만 유지
    """
    h, w = floor_grid.shape

    # ── 벽까지 거리 + 스무딩 ──
    dist = distance_transform_edt(floor_grid == 0) * cell_size
    from scipy.ndimage import gaussian_filter
    dist = gaussian_filter(dist, sigma=2.0)

    # ── Ridge 추출: 국소 최댓값 ──
    ridge = np.zeros((h, w), dtype=np.uint8)

    for i in range(1, h - 1):
        for j in range(1, w - 1):
            val = dist[i, j]
            if val < RIDGE_MIN_DIST or val > RIDGE_MAX_DIST:
                continue

            # 4방향 쌍 중 하나라도 양쪽보다 크거나 같으면 능선
            is_ridge = (
                (val >= dist[i - 1, j] and val >= dist[i + 1, j]) or
                (val >= dist[i, j - 1] and val >= dist[i, j + 1]) or
                (val >= dist[i - 1, j - 1] and val >= dist[i + 1, j + 1]) or
                (val >= dist[i - 1, j + 1] and val >= dist[i + 1, j - 1])
            )
            if is_ridge:
                ridge[i, j] = 1

    # ── Ridge thinning: 1픽셀 폭으로 축소 ──
    ridge = skeletonize(ridge)

    # ── 카메라 궤적과 연결된 영역만 유지 ──
    if cam_positions is not None and len(cam_positions) > 0:
        labeled, n_labels = label(ridge)
        if n_labels > 1:
            cx = ((cam_positions[:, 0] - x_min) / cell_size).astype(int)
            cy = ((cam_positions[:, 1] - y_min) / cell_size).astype(int)

            # 카메라 위치에서 가장 가까운 ridge 셀의 레이블 수집
            cam_labels = set()
            search_r = int(2.0 / cell_size)  # 카메라에서 2m 이내 ridge 탐색
            for ci in range(len(cx)):
                if not (0 <= cx[ci] < w and 0 <= cy[ci] < h):
                    continue
                for dy in range(-search_r, search_r + 1):
                    for dx in range(-search_r, search_r + 1):
                        ny, nx = cy[ci] + dy, cx[ci] + dx
                        if 0 <= ny < h and 0 <= nx < w:
                            lbl = labeled[ny, nx]
                            if lbl > 0:
                                cam_labels.add(lbl)

            if cam_labels:
                keep = np.zeros((h, w), dtype=np.uint8)
                for lbl in cam_labels:
                    keep[labeled == lbl] = 1
                ridge = keep

    return ridge


def extract_walkable_area(floor_grid, cam_positions=None, x_min=0, y_min=0,
                          cell_size=CELL_SIZE):
    """extract_ridge의 호환용 래퍼."""
    return extract_ridge(floor_grid, cam_positions, x_min, y_min, cell_size)


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


SPUR_MIN_LENGTH = 10.0  # 이 길이(미터) 미만인 가지는 제거


def remove_spurs(skeleton, cell_size=CELL_SIZE, min_length=SPUR_MIN_LENGTH):
    """
    스켈레톤에서 짧은 가지(spur)를 제거한다.

    끝점에서 시작해 분기점까지의 경로 길이가 min_length 미만이면
    해당 가지를 삭제한다. 반복 적용하여 연쇄 spur도 처리.
    """
    img = skeleton.copy()
    rows, cols = img.shape
    min_px = int(min_length / cell_size)

    changed = True
    while changed:
        changed = False

        # 이웃 수 계산
        nc = np.zeros_like(img, dtype=int)
        for i in range(1, rows - 1):
            for j in range(1, cols - 1):
                if img[i, j] == 0:
                    continue
                count = 0
                for di in [-1, 0, 1]:
                    for dj in [-1, 0, 1]:
                        if di == 0 and dj == 0:
                            continue
                        if img[i + di, j + dj] > 0:
                            count += 1
                nc[i, j] = count

        # 끝점(이웃=1)에서 분기점(이웃>=3)까지 추적
        endpoints = list(zip(*np.where(nc == 1)))

        for ey, ex in endpoints:
            # 끝점에서 경로 추적
            path = [(ey, ex)]
            ci, cj = ey, ex
            prev_i, prev_j = -1, -1

            while True:
                next_found = False
                for di in [-1, 0, 1]:
                    for dj in [-1, 0, 1]:
                        if di == 0 and dj == 0:
                            continue
                        ni, nj = ci + di, cj + dj
                        if ni == prev_i and nj == prev_j:
                            continue
                        if 0 <= ni < rows and 0 <= nj < cols and img[ni, nj] > 0:
                            prev_i, prev_j = ci, cj
                            ci, cj = ni, nj
                            path.append((ci, cj))
                            next_found = True
                            break
                    if next_found:
                        break

                if not next_found:
                    break

                # 분기점 도달
                if nc[ci, cj] >= 3:
                    break

                if len(path) > min_px:
                    break

            # 분기점에 도달했고 길이가 짧으면 제거
            if len(path) <= min_px and nc[ci, cj] >= 3:
                # 분기점 자체는 남기고, 그 전까지 삭제
                for py, px in path[:-1]:
                    img[py, px] = 0
                changed = True

    return img


# =============================================================================
# Step 6: 스켈레톤에서 그래프 추출
# =============================================================================

WAYPOINT_INTERVAL = 1.0  # 중간 노드 배치 간격 (미터)
SMOOTH_WINDOW = 15       # 경로 스무딩 윈도우 크기
MIN_EDGE_LENGTH = 0.4    # 이 길이 미만인 엣지는 병합


def _smooth_path_pixels(path, window=SMOOTH_WINDOW):
    """픽셀 경로에 이동 평균 스무딩을 적용한다."""
    if len(path) <= window:
        return path

    arr = np.array(path, dtype=float)
    smoothed = np.copy(arr)
    half = window // 2

    for i in range(half, len(arr) - half):
        smoothed[i] = np.mean(arr[i - half:i + half + 1], axis=0)

    # 시작점/끝점은 원본 유지 (노드 위치 보존)
    smoothed[0] = arr[0]
    smoothed[-1] = arr[-1]

    return [(int(round(y)), int(round(x))) for y, x in smoothed]


def skeleton_to_graph(skeleton, x_min, y_min, cell_size, z_height):
    """
    스켈레톤 이미지에서 노드와 엣지를 추출한다.

    - 분기점 (이웃 3개+) = JUNCTION
    - 끝점 (이웃 1개) = ENDPOINT
    - 경로 위 일정 간격마다 = WAYPOINT (최단경로 정밀도 확보)
    - 경로는 이동 평균으로 스무딩 (지그재그 제거)
    """
    rows, cols = skeleton.shape

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

    # 키 노드 추출: 끝점(이웃=1) 또는 분기점(이웃≥3)
    key_node_set = set()
    for i in range(1, rows - 1):
        for j in range(1, cols - 1):
            if skeleton[i, j] == 0:
                continue
            nc = neighbor_count[i, j]
            if nc == 1 or nc >= 3:
                key_node_set.add((i, j))

    # 키 노드 사이의 경로를 추적하고 스무딩 + 중간 노드 배치
    nodes = []
    node_map = {}  # (y, x) → node_index
    edges = []
    visited_edges = set()  # (start_grid, end_grid) 중복 방지

    def _get_or_create_node(grid_pos, node_type):
        if grid_pos in node_map:
            return node_map[grid_pos]
        i, j = grid_pos
        node_idx = len(nodes)
        nodes.append({
            'id': node_idx,
            'x': float(x_min + j * cell_size),
            'y': float(y_min + i * cell_size),
            'z': float(z_height),
            'type': node_type,
            'grid_pos': grid_pos,
        })
        node_map[grid_pos] = node_idx
        return node_idx

    # 키 노드 먼저 등록
    for pos in key_node_set:
        nc = neighbor_count[pos[0], pos[1]]
        _get_or_create_node(pos, 'ENDPOINT' if nc == 1 else 'JUNCTION')

    # 각 키 노드에서 경로 추적
    for start_pos in list(key_node_set):
        si, sj = start_pos

        for di in [-1, 0, 1]:
            for dj in [-1, 0, 1]:
                if di == 0 and dj == 0:
                    continue
                ni, nj = si + di, sj + dj
                if not (0 <= ni < rows and 0 <= nj < cols):
                    continue
                if skeleton[ni, nj] == 0:
                    continue

                # 경로 픽셀을 모두 기록하며 다음 키 노드까지 추적
                path = [start_pos, (ni, nj)]
                ci, cj = ni, nj
                prev_i, prev_j = si, sj
                found_end = None

                while True:
                    if (ci, cj) in key_node_set and (ci, cj) != start_pos:
                        found_end = (ci, cj)
                        break

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
                                path.append((ci, cj))
                                next_found = True
                                break
                        if next_found:
                            break

                    if not next_found:
                        break
                    if len(path) > rows * cols:
                        break

                if found_end is None:
                    continue

                edge_key = tuple(sorted([start_pos, found_end]))
                if edge_key in visited_edges:
                    continue
                visited_edges.add(edge_key)

                # 경로 스무딩
                smoothed = _smooth_path_pixels(path)

                # 스무딩된 경로를 월드 좌표로 변환
                world_path = []
                for py, px in smoothed:
                    wx = x_min + px * cell_size
                    wy = y_min + py * cell_size
                    world_path.append((wx, wy))

                # 월드 좌표 기준 WAYPOINT_INTERVAL 간격으로 균일 배치
                chain_node_ids = [node_map[start_pos]]
                accumulated_m = 0.0

                for k in range(1, len(world_path)):
                    dx = world_path[k][0] - world_path[k-1][0]
                    dy = world_path[k][1] - world_path[k-1][1]
                    step_m = np.sqrt(dx*dx + dy*dy)
                    accumulated_m += step_m

                    if accumulated_m >= WAYPOINT_INTERVAL and smoothed[k] != found_end:
                        wp_id = _get_or_create_node(smoothed[k], 'WAYPOINT')
                        chain_node_ids.append(wp_id)
                        accumulated_m = 0.0

                chain_node_ids.append(node_map[found_end])

                # 체인의 연속 노드를 엣지로 연결
                for k in range(len(chain_node_ids) - 1):
                    na = nodes[chain_node_ids[k]]
                    nb = nodes[chain_node_ids[k + 1]]
                    dist = np.sqrt((na['x'] - nb['x'])**2 + (na['y'] - nb['y'])**2)
                    edges.append({
                        'from': chain_node_ids[k],
                        'to': chain_node_ids[k + 1],
                        'distance': float(dist),
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


# =============================================================================
# 파이프라인 실행 + 포맷 변환
# =============================================================================

def build_pointcloud_graph(db_path):
    """
    RTAB-Map DB에서 포인트클라우드를 추출하고
    2D 점유격자 기반 그래프를 생성한다.

    Returns:
        floor_graphs: {층번호: (nodes_dict, edges_dict)}
        floors_data: 층별 데이터
        peaks: 층별 Z 피크
        world_points: 전체 포인트클라우드
        cam_positions: 카메라 궤적
    """
    world_points, cam_positions = extract_pointcloud(db_path)
    floors, peaks = separate_floors_by_z(world_points, cam_positions)

    floor_graphs = {}
    node_id_offset = 0

    for fi, fdata in sorted(floors.items()):
        grid, x_min, y_min, w, h = create_occupancy_grid(fdata, CELL_SIZE)
        walkable = extract_walkable_area(grid)
        skel = skeletonize(walkable)
        skel = remove_spurs(skel, CELL_SIZE)
        nodes, edges = skeleton_to_graph(skel, x_min, y_min, CELL_SIZE, peaks[fi])

        for n in nodes:
            n['id'] += node_id_offset
        for e in edges:
            e['from'] += node_id_offset
            e['to'] += node_id_offset

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

        fdata['grid'] = grid
        fdata['walkable'] = walkable
        fdata['skeleton'] = skel
        fdata['nodes'] = nodes
        fdata['edges'] = edges

        # 이상치 노드 제거
        nodes, edges = _remove_outlier_nodes(nodes, edges)

        # 경로 직선화 + 균일 간격 리샘플링
        nodes, edges = _beautify_graph(nodes, edges, WAYPOINT_INTERVAL)

        floor_level = fi + 1
        nodes_dict = _convert_nodes(nodes, floor_level)
        edges_dict = _convert_edges(edges, nodes_dict)
        floor_graphs[floor_level] = (nodes_dict, edges_dict)
        node_id_offset += len(nodes)

    return floor_graphs, floors, peaks, world_points, cam_positions


JUNCTION_MERGE_RADIUS = 1.5  # 이 거리 이내의 분기점은 하나로 병합 (미터)


def _beautify_graph(nodes, edges, interval):
    """
    그래프 토폴로지를 유지하면서 경로를 직선화하고 균일 간격으로 리샘플링.

    1. 키 노드(분기점/끝점) 식별
    2. 키 노드 사이의 체인 추출
    3. 각 체인을 RDP → 각도 스냅 → 직선화
    4. 직선화된 경로 위에 interval 간격으로 새 노드 배치
    5. 새 그래프 조립
    """
    if not nodes or not edges:
        return nodes, edges

    # 인접 리스트 구축
    adj = {}
    for n in nodes:
        adj[n['id']] = []
    for e in edges:
        adj.setdefault(e['from'], []).append(e['to'])
        adj.setdefault(e['to'], []).append(e['from'])

    # 키 노드: 연결 수 != 2 (분기점, 끝점)
    key_ids = {nid for nid, neighbors in adj.items() if len(neighbors) != 2}

    # 연결 수 0인 고립 노드도 키 노드
    for n in nodes:
        if n['id'] not in adj or not adj[n['id']]:
            key_ids.add(n['id'])

    node_by_id = {n['id']: n for n in nodes}

    # 체인 추출: 키 노드 → 키 노드 사이의 경로
    chains = []
    visited_pairs = set()

    for start_id in key_ids:
        for next_id in adj.get(start_id, []):
            pair_key = tuple(sorted([start_id, next_id]))
            if pair_key in visited_pairs:
                continue

            # 체인 추적
            chain = [start_id, next_id]
            curr = next_id
            prev = start_id

            while curr not in key_ids:
                neighbors = adj.get(curr, [])
                next_hop = [n for n in neighbors if n != prev]
                if not next_hop:
                    break
                prev = curr
                curr = next_hop[0]
                chain.append(curr)

            # 시작-끝 쌍 중복 방지
            chain_key = tuple(sorted([chain[0], chain[-1]]))
            if chain_key in visited_pairs:
                continue
            visited_pairs.add(chain_key)

            # 체인의 좌표 수집
            coords = []
            for nid in chain:
                n = node_by_id.get(nid)
                if n:
                    coords.append([n['x'], n['y']])

            if len(coords) >= 2:
                chains.append({
                    'start_id': chain[0],
                    'end_id': chain[-1],
                    'coords': np.array(coords),
                    'z': node_by_id[chain[0]]['z'],
                })

    # 새 그래프 조립
    new_nodes = []
    new_edges = []
    new_node_map = {}  # (x_round, y_round) → node
    next_id = 0

    def _add_node(x, y, z, node_type):
        nonlocal next_id
        # 좌표 반올림으로 중복 체크 (1cm 정밀도)
        key = (round(x, 2), round(y, 2))
        if key in new_node_map:
            return new_node_map[key]['id']
        node = {
            'id': next_id,
            'x': float(x),
            'y': float(y),
            'z': float(z),
            'type': node_type,
            'grid_pos': (0, 0),
        }
        new_nodes.append(node)
        new_node_map[key] = node
        next_id += 1
        return node['id']

    for chain in chains:
        coords = chain['coords']
        z = chain['z']
        start_key_id = chain['start_id']
        end_key_id = chain['end_id']

        # 1. RDP 단순화
        simplified = _rdp(coords, epsilon=0.3)

        # 2. 스플라인 스무딩 (부드러운 곡선)
        smoothed = _smooth_spline(simplified)

        # 3. 균일 간격 리샘플링
        resampled = _resample_path(smoothed, interval)

        if len(resampled) < 2:
            resampled = np.array([coords[0], coords[-1]])

        # 4. 노드 생성
        chain_node_ids = []
        for i, pt in enumerate(resampled):
            if i == 0:
                ntype = node_by_id[start_key_id].get('type', 'ENDPOINT')
                if len(adj.get(start_key_id, [])) >= 3:
                    ntype = 'JUNCTION'
            elif i == len(resampled) - 1:
                ntype = node_by_id[end_key_id].get('type', 'ENDPOINT')
                if len(adj.get(end_key_id, [])) >= 3:
                    ntype = 'JUNCTION'
            else:
                ntype = 'WAYPOINT'

            nid = _add_node(pt[0], pt[1], z, ntype)
            chain_node_ids.append(nid)

        # 5. 엣지 생성
        for i in range(len(chain_node_ids) - 1):
            a = chain_node_ids[i]
            b = chain_node_ids[i + 1]
            if a == b:
                continue
            na = new_node_map.get(
                (round(new_nodes[a]['x'], 2), round(new_nodes[a]['y'], 2)),
                new_nodes[a]
            )
            nb_node = next((n for n in new_nodes if n['id'] == b), None)
            na_node = next((n for n in new_nodes if n['id'] == a), None)
            if na_node and nb_node:
                dist = float(np.sqrt(
                    (na_node['x'] - nb_node['x'])**2 +
                    (na_node['y'] - nb_node['y'])**2
                ))
                if dist > 0.01:
                    new_edges.append({
                        'from': a,
                        'to': b,
                        'distance': dist,
                    })

    # 중복 엣지 제거
    seen = set()
    unique_edges = []
    for e in new_edges:
        key = tuple(sorted([e['from'], e['to']]))
        if key not in seen:
            seen.add(key)
            unique_edges.append(e)

    # 가까운 분기 노드 병합
    new_nodes, unique_edges = _merge_nearby_junctions(
        new_nodes, unique_edges, JUNCTION_MERGE_RADIUS
    )

    return new_nodes, unique_edges


def _rdp(points, epsilon):
    """Ramer-Douglas-Peucker 단순화"""
    if len(points) <= 2:
        return points

    # 시작-끝 직선에서 가장 먼 점 찾기
    start = points[0]
    end = points[-1]
    line_vec = end - start
    line_len = np.linalg.norm(line_vec)

    if line_len < 1e-10:
        return np.array([start, end])

    line_unit = line_vec / line_len

    max_dist = 0
    max_idx = 0
    for i in range(1, len(points) - 1):
        vec = points[i] - start
        proj = np.dot(vec, line_unit)
        proj = np.clip(proj, 0, line_len)
        closest = start + proj * line_unit
        dist = np.linalg.norm(points[i] - closest)
        if dist > max_dist:
            max_dist = dist
            max_idx = i

    if max_dist > epsilon:
        left = _rdp(points[:max_idx + 1], epsilon)
        right = _rdp(points[max_idx:], epsilon)
        return np.vstack([left[:-1], right])
    else:
        return np.array([start, end])


def _smooth_spline(points):
    """
    RDP로 단순화된 경로를 스플라인 보간으로 부드럽게 만든다.
    시작/끝점은 유지하면서 중간 경로를 부드러운 곡선으로.
    """
    if len(points) <= 2:
        return points

    from scipy.interpolate import splprep, splev

    try:
        x = points[:, 0]
        y = points[:, 1]

        # 누적 거리를 매개변수로 사용
        dists = np.sqrt(np.diff(x)**2 + np.diff(y)**2)
        cum_dist = np.concatenate([[0], np.cumsum(dists)])
        total = cum_dist[-1]

        if total < 1e-6:
            return points

        # 정규화
        u = cum_dist / total

        # 스플라인 피팅 (s: 스무딩 팩터, 클수록 부드러움)
        k = min(3, len(points) - 1)  # 차수
        s = len(points) * 0.5  # 스무딩 강도
        tck, _ = splprep([x, y], u=u, k=k, s=s)

        # 촘촘하게 평가
        n_eval = max(len(points) * 3, 20)
        u_new = np.linspace(0, 1, n_eval)
        x_new, y_new = splev(u_new, tck)

        # 시작/끝점 강제 고정
        x_new[0], y_new[0] = points[0]
        x_new[-1], y_new[-1] = points[-1]

        return np.column_stack([x_new, y_new])

    except Exception:
        # 스플라인 실패 시 원본 반환
        return points


def _merge_nearby_junctions(nodes, edges, radius):
    """
    radius 이내의 분기 노드들을 하나로 병합한다.
    병합된 노드의 좌표는 무게중심.
    """
    if not nodes or not edges:
        return nodes, edges

    # 분기 노드 찾기 (연결 수 > 2 또는 type이 JUNCTION)
    conn = {}
    for e in edges:
        conn[e['from']] = conn.get(e['from'], 0) + 1
        conn[e['to']] = conn.get(e['to'], 0) + 1

    junction_ids = [
        n['id'] for n in nodes
        if conn.get(n['id'], 0) > 2 or n.get('type') == 'JUNCTION'
    ]

    if len(junction_ids) < 2:
        return nodes, edges

    node_by_id = {n['id']: n for n in nodes}

    # 가까운 분기 노드끼리 그룹화 (Union-Find)
    parent = {jid: jid for jid in junction_ids}

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(a, b):
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[ra] = rb

    for i in range(len(junction_ids)):
        for j in range(i + 1, len(junction_ids)):
            a, b = junction_ids[i], junction_ids[j]
            na, nb = node_by_id[a], node_by_id[b]
            dist = np.sqrt((na['x'] - nb['x'])**2 + (na['y'] - nb['y'])**2)
            if dist <= radius:
                union(a, b)

    # 그룹별 대표 노드 결정 (무게중심)
    groups = {}
    for jid in junction_ids:
        root = find(jid)
        groups.setdefault(root, []).append(jid)

    # 병합 매핑: old_id → new_id
    merge_map = {}
    for root, members in groups.items():
        if len(members) <= 1:
            continue

        # 무게중심 계산
        xs = [node_by_id[m]['x'] for m in members]
        ys = [node_by_id[m]['y'] for m in members]
        cx, cy = np.mean(xs), np.mean(ys)

        # 대표 노드 = 무게중심에 가장 가까운 노드
        best = min(members, key=lambda m: (node_by_id[m]['x'] - cx)**2 + (node_by_id[m]['y'] - cy)**2)

        # 대표 노드 좌표를 무게중심으로 이동
        node_by_id[best]['x'] = float(cx)
        node_by_id[best]['y'] = float(cy)
        node_by_id[best]['type'] = 'JUNCTION'

        for m in members:
            if m != best:
                merge_map[m] = best

    if not merge_map:
        return nodes, edges

    # 노드 필터링
    remove_ids = set(merge_map.keys())
    nodes = [n for n in nodes if n['id'] not in remove_ids]

    # 엣지 리매핑
    new_edges = []
    seen = set()
    for e in edges:
        f = merge_map.get(e['from'], e['from'])
        t = merge_map.get(e['to'], e['to'])
        if f == t:
            continue
        key = tuple(sorted([f, t]))
        if key in seen:
            continue
        seen.add(key)

        nf = node_by_id.get(f)
        nt = node_by_id.get(t)
        if nf and nt:
            dist = float(np.sqrt((nf['x'] - nt['x'])**2 + (nf['y'] - nt['y'])**2))
            new_edges.append({'from': f, 'to': t, 'distance': dist})

    return nodes, new_edges


def _resample_path(points, interval):
    """경로를 interval 간격으로 균일하게 리샘플링. 시작/끝점은 유지."""
    if len(points) < 2:
        return points

    # 누적 거리 계산
    dists = [0.0]
    for i in range(1, len(points)):
        d = np.linalg.norm(points[i] - points[i-1])
        dists.append(dists[-1] + d)

    total_length = dists[-1]
    if total_length < interval:
        return np.array([points[0], points[-1]])

    # 균일 간격 위치 생성
    n_segments = max(1, round(total_length / interval))
    actual_interval = total_length / n_segments
    target_dists = [i * actual_interval for i in range(n_segments + 1)]

    # 보간
    resampled = []
    seg_idx = 0
    for td in target_dists:
        while seg_idx < len(dists) - 1 and dists[seg_idx + 1] < td:
            seg_idx += 1

        if seg_idx >= len(dists) - 1:
            resampled.append(points[-1].copy())
            continue

        seg_len = dists[seg_idx + 1] - dists[seg_idx]
        if seg_len < 1e-10:
            resampled.append(points[seg_idx].copy())
            continue

        t = (td - dists[seg_idx]) / seg_len
        pt = points[seg_idx] * (1 - t) + points[seg_idx + 1] * t
        resampled.append(pt)

    return np.array(resampled)


def _remove_outlier_nodes(nodes, edges, min_group_size=3):
    """
    메인 그래프에서 분리된 소그룹/고립 노드를 제거한다.

    연결 요소 분석으로 가장 큰 그룹만 유지.
    min_group_size 미만인 연결 요소는 이상치로 간주.
    """
    if not nodes or not edges:
        return nodes, edges

    node_ids = {n['id'] for n in nodes}

    # 인접 리스트 구축
    adj = {nid: set() for nid in node_ids}
    for e in edges:
        if e['from'] in adj and e['to'] in adj:
            adj[e['from']].add(e['to'])
            adj[e['to']].add(e['from'])

    # BFS로 연결 요소 찾기
    visited = set()
    components = []

    for nid in node_ids:
        if nid in visited:
            continue
        component = set()
        queue = [nid]
        while queue:
            curr = queue.pop(0)
            if curr in visited:
                continue
            visited.add(curr)
            component.add(curr)
            for neighbor in adj.get(curr, []):
                if neighbor not in visited:
                    queue.append(neighbor)
        components.append(component)

    if not components:
        return nodes, edges

    # 가장 큰 연결 요소 유지 + min_group_size 이상인 요소도 유지
    largest = max(components, key=len)
    keep_ids = set()
    for comp in components:
        if comp == largest or len(comp) >= min_group_size:
            keep_ids.update(comp)

    removed = node_ids - keep_ids
    if removed:
        nodes = [n for n in nodes if n['id'] in keep_ids]
        edges = [e for e in edges if e['from'] in keep_ids and e['to'] in keep_ids]

    return nodes, edges


def _merge_short_edges(nodes, edges, min_length):
    """
    짧은 엣지를 병합한다.

    짧은 엣지의 양쪽 노드 중 연결 수가 적은 쪽을 많은 쪽으로 흡수.
    연결 수 같으면 WAYPOINT를 우선 제거.
    """
    changed = True
    while changed:
        changed = False

        # 연결 수 계산
        conn = {}
        for e in edges:
            conn[e['from']] = conn.get(e['from'], 0) + 1
            conn[e['to']] = conn.get(e['to'], 0) + 1

        # 가장 짧은 엣지부터 처리
        short_edges = sorted(
            [e for e in edges if e['distance'] < min_length],
            key=lambda x: x['distance']
        )

        for e in short_edges:
            a, b = e['from'], e['to']
            ca = conn.get(a, 0)
            cb = conn.get(b, 0)

            # 둘 다 연결 수 1이면 (고립 쌍) 스킵
            if ca <= 1 and cb <= 1:
                continue

            # 제거할 노드: 연결 수 적은 쪽, 같으면 WAYPOINT 우선
            na = next((n for n in nodes if n['id'] == a), None)
            nb = next((n for n in nodes if n['id'] == b), None)
            if not na or not nb:
                continue

            if ca < cb:
                remove_id, keep_id = a, b
            elif cb < ca:
                remove_id, keep_id = b, a
            elif na.get('type') == 'WAYPOINT':
                remove_id, keep_id = a, b
            elif nb.get('type') == 'WAYPOINT':
                remove_id, keep_id = b, a
            else:
                remove_id, keep_id = a, b

            # remove_id의 모든 엣지를 keep_id로 이관
            new_edges = []
            for ex in edges:
                if ex is e:
                    continue  # 짧은 엣지 자체는 삭제
                fa, ta = ex['from'], ex['to']
                if fa == remove_id:
                    fa = keep_id
                if ta == remove_id:
                    ta = keep_id
                if fa == ta:
                    continue  # 자기 루프 방지
                new_edges.append({'from': fa, 'to': ta, 'distance': ex['distance']})

            # 거리 재계산
            keep_node = next(n for n in nodes if n['id'] == keep_id)
            for ex in new_edges:
                n_from = next((n for n in nodes if n['id'] == ex['from']), None)
                n_to = next((n for n in nodes if n['id'] == ex['to']), None)
                if n_from and n_to:
                    ex['distance'] = float(np.sqrt(
                        (n_from['x'] - n_to['x'])**2 + (n_from['y'] - n_to['y'])**2
                    ))

            edges = new_edges
            nodes = [n for n in nodes if n['id'] != remove_id]
            changed = True
            break

    # 중복 엣지 제거
    seen = set()
    unique_edges = []
    for e in edges:
        key = tuple(sorted([e['from'], e['to']]))
        if key not in seen:
            seen.add(key)
            unique_edges.append(e)

    return nodes, unique_edges


def _convert_nodes(pc_nodes, floor_level):
    """pointcloud 노드를 path_service 포맷으로 변환"""
    result = []
    for n in pc_nodes:
        node_type = n.get('type', 'WAYPOINT')
        if node_type == 'HUB_ENTRY':
            node_type = 'JUNCTION'
        result.append({
            'id': str(uuid.uuid4()),
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
    """pointcloud 엣지를 path_service 포맷으로 변환"""
    id_map = {n['_pc_id']: n['id'] for n in nodes_dict}
    result = []
    for e in pc_edges:
        from_id = id_map.get(e['from'])
        to_id = id_map.get(e['to'])
        if not from_id or not to_id:
            continue
        result.append({
            'id': str(uuid.uuid4()),
            'from_node_id': from_id,
            'to_node_id': to_id,
            'distance': e['distance'],
            'is_bidirectional': True,
            'edge_type': 'HORIZONTAL',
            'metadata': {},
        })
    return result
