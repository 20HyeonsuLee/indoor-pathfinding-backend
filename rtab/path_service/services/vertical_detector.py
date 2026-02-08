"""
=============================================================================
수직 통로 감지 모듈 (Vertical Passage Detection Module)
=============================================================================

이 모듈은 카메라 궤적에서 계단과 엘리베이터를 감지하고 층을 분리합니다.

[수직 통로의 특징]
카메라 궤적에서 Z값(높이)이 연속적으로 변하는 구간이 있으면
계단이나 엘리베이터를 이용한 것입니다.

    계단의 특징:                 엘리베이터의 특징:
    ┌─────────────────┐        ┌─────────────────┐
    │     /           │        │        │        │
    │    /            │        │        │        │
    │   /  (경사)     │        │        ↓ (수직) │
    │  /              │        │        │        │
    │ /               │        │        │        │
    └─────────────────┘        └─────────────────┘
    XY 이동 많음               XY 이동 거의 없음
    XY/Z 비율 > 1.0           XY/Z 비율 < 1.0

[층 분리 방법]
Z값의 히스토그램을 분석하여 각 층의 높이를 식별합니다.

    Z값 히스토그램:
    ▲
    │ ██████              ← 3층 (z ≈ 6.0m)
    │
    │      ██████         ← 2층 (z ≈ 3.0m)
    │
    │           ██████    ← 1층 (z ≈ 0.0m)
    └────────────────────→ Z값

[사용 예시]
    from services.vertical_detector import detect_stairs_first, separate_floors

    # Step 1: 계단/엘리베이터 구간 먼저 감지
    passages, stair_mask = detect_stairs_first(positions)

    # Step 2: 수직 통로 구간 제외하고 층 분리
    floors = separate_floors(positions, node_ids, stair_mask=stair_mask)
"""

import numpy as np
from typing import List, Dict, Tuple, Optional
from scipy.ndimage import gaussian_filter1d


# =============================================================================
# 상수 정의
# =============================================================================

# 수직 이동 감지 파라미터
Z_CHANGE_THRESHOLD = 0.05      # Z 변화 감지 최소값 (미터/스텝)
WINDOW_SIZE = 10               # 슬라이딩 윈도우 크기 (포인트 수)
MIN_TOTAL_Z_CHANGE = 1.5       # 최소 총 Z 변화량 (미터) - 한 층 높이의 절반
MIN_STAIR_POINTS = 5           # 최소 계단 구간 포인트 수

# 계단 vs 엘리베이터 판정
ELEVATOR_XY_Z_RATIO = 1.0      # 이 비율 미만이면 엘리베이터

# 층 분리 파라미터
DEFAULT_FLOOR_HEIGHT = 3.0     # 층 간 기본 높이 (미터)
MIN_POINTS_PER_FLOOR = 10      # 층당 최소 포인트 수

# 갈림길 병합 파라미터
GAP_THRESHOLD = 10             # 계단 구간 병합 거리 (포인트 수)


# =============================================================================
# 수직 통로 감지 (계단/엘리베이터)
# =============================================================================

def detect_stairs_first(
    positions: np.ndarray,
    z_change_threshold: float = Z_CHANGE_THRESHOLD,
    window_size: int = WINDOW_SIZE,
    min_total_z_change: float = MIN_TOTAL_Z_CHANGE,
    min_stair_points: int = MIN_STAIR_POINTS
) -> Tuple[List[Dict], np.ndarray]:
    """
    경로에서 계단/엘리베이터 구간을 먼저 감지합니다.

    [감지 알고리즘 - 슬라이딩 윈도우]
    1. 윈도우를 경로를 따라 이동시키며 Z값 변화 측정
    2. 윈도우 내 Z 변화가 임계값 이상이면 수직 이동으로 판정
    3. 연속된 수직 이동 구간을 하나의 계단/엘리베이터로 그룹화

    [계단 vs 엘리베이터 판정]
    XY/Z 비율로 구분합니다:
    - XY 이동이 많으면 (비율 > 1.0): 계단 (경사로 이동)
    - XY 이동이 적으면 (비율 < 1.0): 엘리베이터 (수직 이동)

    [반환 데이터]
    1. passages: 수직 통로 정보 리스트
       - type: 'STAIRCASE' 또는 'ELEVATOR'
       - start_idx, end_idx: 구간 인덱스
       - z_start, z_end: 시작/끝 높이
       - xy_z_ratio: XY/Z 이동 비율

    2. stair_mask: 수직 통로 포인트 표시 배열
       - True: 계단/엘리베이터 구간
       - False: 일반 층 구간

    Args:
        positions: [N, 3] 좌표 배열
        z_change_threshold: Z 변화 감지 임계값
        window_size: 슬라이딩 윈도우 크기
        min_total_z_change: 최소 총 Z 변화량
        min_stair_points: 최소 구간 포인트 수

    Returns:
        (수직 통로 리스트, 수직 통로 마스크 배열)

    Examples:
        >>> passages, mask = detect_stairs_first(positions)
        >>> for p in passages:
        ...     print(f"{p['type']}: {p['z_start']:.1f}m → {p['z_end']:.1f}m")
    """
    n = len(positions)
    is_stair = np.zeros(n, dtype=bool)  # 수직 통로 마스크
    z = positions[:, 2]

    # Z값의 변화량 (연속 포인트 간)
    z_diff = np.diff(z)

    # Step 1: 슬라이딩 윈도우로 수직 이동 구간 찾기
    changing_z = np.zeros(n, dtype=bool)

    for i in range(n - window_size):
        # 윈도우 내 총 Z 변화
        window_z_change = z[i + window_size] - z[i]

        # 충분한 Z 변화가 있는지 확인
        if abs(window_z_change) > min_total_z_change * (window_size / 20):
            # 방향 일관성 확인 (올라가기만 또는 내려가기만)
            window_diff = z_diff[i:i + window_size]

            if window_z_change > 0:
                # 올라가는 경우: 양수 변화가 절반 이상
                consistent = np.sum(window_diff > z_change_threshold / 2) > window_size * 0.5
            else:
                # 내려가는 경우: 음수 변화가 절반 이상
                consistent = np.sum(window_diff < -z_change_threshold / 2) > window_size * 0.5

            if consistent:
                # 해당 윈도우를 수직 이동으로 표시
                changing_z[i:i + window_size + 1] = True

    # Step 2: 연속된 수직 이동 구간을 그룹화
    stair_segments = []
    i = 0

    while i < n:
        if changing_z[i]:
            start_idx = i

            # 구간 끝 찾기
            while i < n and changing_z[i]:
                i += 1
            end_idx = i

            # 구간 분석
            segment_positions = positions[start_idx:end_idx]
            z_start = segment_positions[0, 2]
            z_end = segment_positions[-1, 2]
            z_disp = abs(z_end - z_start)

            # 최소 조건 만족 확인
            if z_disp >= min_total_z_change and (end_idx - start_idx) >= min_stair_points:
                # XY 이동 거리 계산 (직선 거리가 아닌 경로 거리)
                xy_diffs = np.diff(segment_positions[:, :2], axis=0)
                total_xy_dist = np.sum(np.sqrt(np.sum(xy_diffs**2, axis=1)))

                # XY/Z 비율로 계단/엘리베이터 판정
                xy_z_ratio = total_xy_dist / z_disp if z_disp > 0 else float('inf')
                passage_type = "ELEVATOR" if xy_z_ratio < ELEVATOR_XY_Z_RATIO else "STAIRCASE"

                stair_segments.append({
                    'type': passage_type,
                    'start_idx': start_idx,
                    'end_idx': end_idx,
                    'positions': segment_positions.copy(),
                    'z_start': float(z_start),
                    'z_end': float(z_end),
                    'z_displacement': float(z_disp),
                    'xy_z_ratio': float(xy_z_ratio),
                    'direction': 'UP' if z_end > z_start else 'DOWN'
                })

                # 마스크 업데이트
                is_stair[start_idx:end_idx] = True
        else:
            i += 1

    # Step 3: 인접한 같은 방향 구간 병합
    stair_segments = _merge_stair_segments(stair_segments, positions)

    return stair_segments, is_stair


def _merge_stair_segments(
    segments: List[Dict],
    positions: np.ndarray,
    gap_threshold: int = GAP_THRESHOLD
) -> List[Dict]:
    """
    인접하고 같은 방향인 계단 구간을 병합합니다.

    [병합 조건]
    1. 두 구간 사이 간격이 gap_threshold 이내
    2. 같은 방향 (둘 다 UP 또는 둘 다 DOWN)

    Args:
        segments: 계단 구간 리스트
        positions: 전체 좌표 배열
        gap_threshold: 최대 병합 간격

    Returns:
        병합된 계단 구간 리스트
    """
    if len(segments) < 2:
        return segments

    merged = []
    current = segments[0].copy()

    for seg in segments[1:]:
        # 같은 방향인지 확인
        same_direction = current['direction'] == seg['direction']
        # 가까운지 확인
        close_enough = seg['start_idx'] - current['end_idx'] < gap_threshold

        if same_direction and close_enough:
            # 병합: 현재 구간 확장
            current['end_idx'] = seg['end_idx']
            current['positions'] = positions[current['start_idx']:current['end_idx']]
            current['z_end'] = seg['z_end']
            current['z_displacement'] = abs(current['z_end'] - current['z_start'])

            # XY/Z 비율 재계산
            xy_diffs = np.diff(current['positions'][:, :2], axis=0)
            total_xy_dist = np.sum(np.sqrt(np.sum(xy_diffs**2, axis=1)))
            z_disp = current['z_displacement']
            current['xy_z_ratio'] = total_xy_dist / z_disp if z_disp > 0 else float('inf')
            current['type'] = "ELEVATOR" if current['xy_z_ratio'] < ELEVATOR_XY_Z_RATIO else "STAIRCASE"
        else:
            merged.append(current)
            current = seg.copy()

    merged.append(current)
    return merged


# =============================================================================
# 층 분리
# =============================================================================

def separate_floors(
    positions: np.ndarray,
    node_ids: List[int],
    height_threshold: float = DEFAULT_FLOOR_HEIGHT,
    min_points_per_floor: int = MIN_POINTS_PER_FLOOR,
    stair_mask: Optional[np.ndarray] = None
) -> Dict[int, Dict]:
    """
    궤적을 층별로 분리합니다.

    [분리 알고리즘]
    1. 수직 통로 구간 제외 (stair_mask 사용)
    2. Z값 히스토그램 분석으로 층 높이 클러스터링
    3. 각 포인트를 가장 가까운 층에 할당

    [반환 데이터 구조]
    {
        층번호: {
            'positions': 해당 층의 좌표 배열,
            'node_ids': 원본 노드 ID 리스트,
            'indices': 원본 배열에서의 인덱스,
            'z_mean': 평균 Z값,
            'z_min', 'z_max': Z값 범위,
            'point_count': 포인트 수
        }
    }

    Args:
        positions: [N, 3] 좌표 배열
        node_ids: 원본 노드 ID 리스트
        height_threshold: 층 간 높이 (미터)
        min_points_per_floor: 층당 최소 포인트 수
        stair_mask: 수직 통로 제외 마스크 (True = 제외)

    Returns:
        층별 데이터 딕셔너리

    Examples:
        >>> floors = separate_floors(positions, node_ids, stair_mask=stair_mask)
        >>> for level, data in floors.items():
        ...     print(f"{level}층: {data['point_count']}개 포인트, Z = {data['z_mean']:.2f}m")
    """
    # Step 1: 수직 통로 제외
    if stair_mask is not None:
        floor_mask = ~stair_mask
        floor_positions = positions[floor_mask]
        floor_node_ids = [node_ids[i] for i in range(len(node_ids)) if floor_mask[i]]
        original_indices = np.where(floor_mask)[0]
    else:
        floor_positions = positions
        floor_node_ids = node_ids
        original_indices = np.arange(len(positions))

    if len(floor_positions) == 0:
        return {}

    # Step 2: Z값 클러스터링으로 층 식별
    z_values = floor_positions[:, 2]
    floor_levels = _cluster_z_values(z_values, height_threshold)

    # Step 3: 층별로 포인트 그룹화
    floors = {}
    unique_levels = np.unique(floor_levels)

    # 유효한 층 클러스터를 Z값 순서로 수집
    valid_clusters = []
    for level in unique_levels:
        mask = floor_levels == level
        level_positions = floor_positions[mask]

        # 최소 포인트 수 확인
        if len(level_positions) < min_points_per_floor:
            continue

        level_node_ids = [floor_node_ids[i] for i in range(len(floor_node_ids)) if mask[i]]
        level_indices = original_indices[mask]
        z_mean = np.mean(level_positions[:, 2])

        valid_clusters.append({
            'positions': level_positions,
            'node_ids': level_node_ids,
            'indices': level_indices,
            'z_mean': float(z_mean),
            'z_min': float(level_positions[:, 2].min()),
            'z_max': float(level_positions[:, 2].max()),
            'point_count': len(level_positions)
        })

    # Z값 순서로 정렬 후 1층부터 순차 번호 부여
    valid_clusters.sort(key=lambda c: c['z_mean'])
    for i, cluster in enumerate(valid_clusters):
        floor_number = i + 1  # 1층부터 시작
        floors[floor_number] = cluster

    return floors


def _cluster_z_values(
    z_values: np.ndarray,
    threshold: float
) -> np.ndarray:
    """
    Z값을 클러스터링하여 층 레벨을 할당합니다.

    [알고리즘 - 히스토그램 기반]
    1. Z값의 히스토그램 생성
    2. 히스토그램 스무딩으로 노이즈 제거
    3. 피크(봉우리) 찾기 = 각 층의 높이
    4. 각 Z값을 가장 가까운 피크에 할당

    Args:
        z_values: Z 좌표 배열
        threshold: 층 간 높이 (클러스터 분리 거리)

    Returns:
        각 포인트의 층 레벨 배열
    """
    z_min, z_max = z_values.min(), z_values.max()
    z_range = z_max - z_min

    # 단일 층인 경우
    if z_range < threshold:
        return np.zeros(len(z_values), dtype=int)

    # Step 1: 히스토그램 생성
    bin_size = 0.5  # 50cm 빈
    num_bins = max(int(z_range / bin_size), 20)
    hist, bin_edges = np.histogram(z_values, bins=num_bins)

    # Step 2: 히스토그램 스무딩
    smoothed_hist = gaussian_filter1d(hist.astype(float), sigma=1.5)

    # Step 3: 유의미한 피크 찾기
    min_count = len(z_values) * 0.03  # 최소 3% 이상
    peaks = _find_histogram_peaks(smoothed_hist, bin_edges, min_count, threshold)

    # 피크를 찾지 못한 경우 균등 분할
    if len(peaks) == 0:
        num_floors = max(1, int(z_range / threshold) + 1)
        peaks = [z_min + (i + 0.5) * (z_range / num_floors) for i in range(num_floors)]

    peaks = sorted(peaks)

    # Step 4: 각 Z값을 가장 가까운 피크에 할당
    peaks_array = np.array(peaks)
    levels = np.zeros(len(z_values), dtype=int)

    for i, z in enumerate(z_values):
        distances = np.abs(peaks_array - z)
        levels[i] = np.argmin(distances)

    return levels


def _find_histogram_peaks(
    smoothed_hist: np.ndarray,
    bin_edges: np.ndarray,
    min_count: float,
    threshold: float
) -> List[float]:
    """
    스무딩된 히스토그램에서 피크(층 높이)를 찾습니다.

    [피크 찾기 방법]
    1. 최소 카운트 이상인 빈 찾기
    2. 연속된 유의미한 빈들을 그룹화
    3. 각 그룹의 가중 평균 = 층 높이

    Args:
        smoothed_hist: 스무딩된 히스토그램
        bin_edges: 빈 경계값
        min_count: 최소 카운트
        threshold: 피크 간 최소 거리

    Returns:
        피크 Z값 리스트
    """
    # 유의미한 빈 찾기
    significant_bins = []
    for i in range(len(smoothed_hist)):
        if smoothed_hist[i] >= min_count:
            significant_bins.append(i)

    if len(significant_bins) == 0:
        return []

    # 연속된 빈들을 그룹화
    regions = []
    current_region = [significant_bins[0]]

    for i in range(1, len(significant_bins)):
        if significant_bins[i] - significant_bins[i - 1] <= 2:
            current_region.append(significant_bins[i])
        else:
            regions.append(current_region)
            current_region = [significant_bins[i]]
    regions.append(current_region)

    # 각 그룹의 가중 평균 계산
    peaks = []
    for region in regions:
        region_z_values = [(bin_edges[i] + bin_edges[i + 1]) / 2 for i in region]
        region_counts = [smoothed_hist[i] for i in region]

        if sum(region_counts) > 0:
            peak_z = np.average(region_z_values, weights=region_counts)

            # 기존 피크와 충분히 떨어져 있는지 확인
            is_new_peak = True
            for existing_peak in peaks:
                if abs(peak_z - existing_peak) < threshold * 0.7:
                    is_new_peak = False
                    break

            if is_new_peak:
                peaks.append(peak_z)

    return peaks


# =============================================================================
# 층 정보 할당
# =============================================================================

def assign_floors_to_stairs(
    stair_segments: List[Dict],
    floors_data: Dict[int, Dict],
    height_threshold: float = DEFAULT_FLOOR_HEIGHT
) -> List[Dict]:
    """
    각 계단 구간에 시작/도착 층을 할당합니다.

    [할당 방법]
    계단의 시작/끝 Z값과 가장 가까운 층의 Z 평균을 비교하여
    해당 층 번호를 할당합니다.

    Args:
        stair_segments: 계단 구간 리스트
        floors_data: 층별 데이터 (z_mean 포함)
        height_threshold: 층 높이 (폴백용)

    Returns:
        층 정보가 추가된 계단 구간 리스트
    """
    for segment in stair_segments:
        z_start = segment['z_start']
        z_end = segment['z_end']

        segment['from_floor'] = _find_nearest_floor(z_start, floors_data)
        segment['to_floor'] = _find_nearest_floor(z_end, floors_data)

    return stair_segments


def _find_nearest_floor(z_value: float, floors_data: Dict[int, Dict]) -> int:
    """Z값과 가장 가까운 층 번호를 반환합니다."""
    if not floors_data:
        return 0

    best_floor = 0
    best_dist = float('inf')
    for floor_num, data in floors_data.items():
        dist = abs(data['z_mean'] - z_value)
        if dist < best_dist:
            best_dist = dist
            best_floor = floor_num

    return best_floor


# =============================================================================
# 유틸리티 함수
# =============================================================================

def detect_vertical_passages(
    positions: np.ndarray,
    floors_data: Dict[int, Dict],
    z_change_threshold: float = 0.5,
    xy_ratio_threshold: float = 0.3
) -> List[Dict]:
    """
    구 버전 호환용 함수입니다.
    detect_stairs_first()를 사용하세요.

    Args:
        positions: 좌표 배열
        floors_data: 층별 데이터
        z_change_threshold: Z 변화 임계값
        xy_ratio_threshold: XY/Z 비율 임계값

    Returns:
        수직 통로 리스트
    """
    passages, _ = detect_stairs_first(positions)
    return assign_floors_to_stairs(passages, floors_data)


def get_vertical_passage_stats(passages: List[Dict]) -> Dict:
    """
    수직 통로 통계를 계산합니다.

    Args:
        passages: 수직 통로 리스트

    Returns:
        통계 딕셔너리
    """
    if not passages:
        return {
            'total_passages': 0,
            'staircase_count': 0,
            'elevator_count': 0,
            'total_z_distance': 0,
            'floors_covered': []
        }

    staircase_count = sum(1 for p in passages if p['type'] == 'STAIRCASE')
    elevator_count = sum(1 for p in passages if p['type'] == 'ELEVATOR')
    total_z = sum(p.get('z_displacement', 0) for p in passages)

    floors = set()
    for p in passages:
        if 'from_floor' in p:
            floors.add(p['from_floor'])
        if 'to_floor' in p:
            floors.add(p['to_floor'])

    return {
        'total_passages': len(passages),
        'staircase_count': staircase_count,
        'elevator_count': elevator_count,
        'total_z_distance': total_z,
        'floors_covered': sorted(list(floors))
    }
