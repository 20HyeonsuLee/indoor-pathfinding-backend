"""
=============================================================================
경로 직선화 모듈 (Path Straightening Module)
=============================================================================

이 모듈은 카메라 흔들림으로 인한 구불구불한 경로를 직선으로 보정합니다.

[문제 상황 - 카메라 흔들림]
사람이 직선 복도를 걸어가면서 SLAM 카메라로 촬영하면,
손 떨림이나 걸음걸이로 인해 실제 직선인 경로가 지그재그로 기록됩니다.

    실제 복도 (직선):
    A ─────────────────────────────────→ B

    카메라로 기록된 경로 (흔들림):
    A ~~~∿∿~~~∿∿~~~∿∿~~~∿∿~~~∿∿~~~∿∿~~→ B
      ↑ 좌우로 흔들림, 위아래로 출렁임

[해결 방법 - 직선 피팅]
1. 경로를 여러 구간으로 분할
2. 각 구간에서 PCA(주성분 분석)로 주 이동 방향 계산
3. 모든 점을 그 직선 위로 투영 (Projection)

    보정 전:  A ~~~∿∿~~~∿∿~~~→ B
                    ↓ 직선 피팅 & 투영
    보정 후:  A ───────────────→ B

[제공 기능]
1. detect_straight_segments: 직선 구간 자동 감지
2. fit_line_pca: PCA로 최적 직선 피팅
3. project_points_to_line: 점들을 직선 위로 투영
4. straighten_path: 전체 경로 직선화

[사용 예시]
    from services.path_flattening import straighten_path

    # 전체 경로 직선화 (권장)
    straightened, stats = straighten_path(positions, segment_length=20)

    # 또는 개별 단계 수행
    segments = detect_straight_segments(positions, min_length=10)
    for start, end in segments:
        segment_points = positions[start:end+1]
        line_params = fit_line_pca(segment_points)
        projected = project_points_to_line(segment_points, line_params)
"""

import numpy as np
from typing import List, Tuple, Dict, Optional
from dataclasses import dataclass

from services.smoothing import split_at_gaps
from services.deduplication import simplify_path_rdp


# =============================================================================
# 상수 정의
# =============================================================================

# 직선 구간 최소 길이 (포인트 수)
DEFAULT_MIN_SEGMENT_LENGTH = 10

# 구간 분할 기본 길이 (포인트 수)
DEFAULT_SEGMENT_LENGTH = 20

# 방향 변화 임계값 (도) - 이 각도 이상 변하면 새 구간
DEFAULT_ANGLE_THRESHOLD = 45.0

# 직선 피팅 품질 임계값 (설명 분산 비율)
# 0.9 = 90% 이상의 분산이 주성분으로 설명되어야 직선으로 인정
DEFAULT_LINEARITY_THRESHOLD = 0.85

# 최대 허용 변위 (미터)
# 직선화 시 원본에서 이 거리 이상 이동하면 해당 구간은 직선화하지 않음
# 카메라 흔들림 = 1~2m, 그 이상은 경로 자체가 곡선/L자임
DEFAULT_MAX_DISPLACEMENT = 2.0


# =============================================================================
# 데이터 클래스
# =============================================================================

@dataclass
class LineParams:
    """
    3D 직선의 파라미터를 저장하는 클래스.

    [3D 직선 표현 - 점-방향 형식]
    직선 위의 임의의 점 P는 다음과 같이 표현됩니다:
        P = point + t * direction
    여기서 t는 실수 파라미터입니다.

        direction (방향 벡터)
            ↗
           /
          /
         ● point (직선 위의 한 점, 보통 중심점)
        /
       /
      ↙

    Attributes:
        point: 직선 위의 한 점 (보통 데이터의 중심점)
        direction: 정규화된 방향 벡터 (길이 1)
        explained_variance_ratio: 주성분이 설명하는 분산 비율 (직선성 지표)
    """
    point: np.ndarray      # 직선 위의 점 (중심점)
    direction: np.ndarray  # 방향 벡터 (정규화됨)
    explained_variance_ratio: float  # 직선성 (0~1, 1에 가까울수록 직선)


# =============================================================================
# 직선 피팅 (PCA 기반)
# =============================================================================

def fit_line_pca(points: np.ndarray) -> LineParams:
    """
    PCA(주성분 분석)를 사용하여 점들에 최적 직선을 피팅합니다.

    [PCA 직선 피팅 원리]
    PCA는 데이터의 분산이 가장 큰 방향을 찾습니다.
    직선 경로의 경우, 이동 방향이 분산이 가장 큰 방향입니다.

        원본 데이터:
            ∗   ∗
          ∗   ∗   ∗
            ∗   ∗
          ∗   ∗
        ────────────→  주성분 방향 (분산 최대)
          ∗
            ∗   ∗

    [explained_variance_ratio 해석]
    - 0.95 이상: 거의 완벽한 직선
    - 0.85~0.95: 직선에 가까움 (약간의 노이즈)
    - 0.7~0.85: 곡선 성분 있음
    - 0.7 미만: 직선이라 보기 어려움

    Args:
        points: [N, 3] 좌표 배열

    Returns:
        LineParams 객체 (직선 파라미터)

    Examples:
        >>> # 거의 직선인 데이터
        >>> points = np.array([[0,0,0], [1,0.1,0], [2,-0.1,0], [3,0.05,0]])
        >>> line = fit_line_pca(points)
        >>> print(f"직선성: {line.explained_variance_ratio:.2f}")  # ~0.99
    """
    # Step 1: 중심점 계산 (평균)
    center = np.mean(points, axis=0)

    # Step 2: 중심을 원점으로 이동 (중심화)
    centered = points - center

    # Step 3: 공분산 행렬 계산
    #         Cov = (1/n) * X^T * X
    #         여기서 X는 중심화된 데이터
    covariance = np.cov(centered.T)

    # Step 4: 고유값 분해 (Eigendecomposition)
    #         공분산 행렬의 고유벡터 = 주성분 방향
    #         고유값 = 해당 방향의 분산
    eigenvalues, eigenvectors = np.linalg.eigh(covariance)

    # Step 5: 가장 큰 고유값에 해당하는 고유벡터 = 주성분 = 직선 방향
    #         (numpy는 고유값을 오름차순 정렬하므로 마지막이 최대)
    max_idx = np.argmax(eigenvalues)
    direction = eigenvectors[:, max_idx]

    # Step 6: 방향 벡터 정규화 (길이 1로)
    direction = direction / np.linalg.norm(direction)

    # Step 7: 설명 분산 비율 계산 (직선성 지표)
    #         가장 큰 고유값 / 전체 고유값 합
    total_variance = np.sum(eigenvalues)
    if total_variance > 1e-10:
        explained_ratio = eigenvalues[max_idx] / total_variance
    else:
        explained_ratio = 1.0

    return LineParams(
        point=center,
        direction=direction,
        explained_variance_ratio=explained_ratio
    )


# =============================================================================
# 점을 직선 위로 투영
# =============================================================================

def project_points_to_line(
    points: np.ndarray,
    line: LineParams,
    preserve_order: bool = True
) -> np.ndarray:
    """
    점들을 직선 위로 수직 투영합니다.

    [투영 원리]
    각 점 P에서 직선에 수선의 발을 내립니다.
    그 수선의 발이 투영된 점 P'입니다.

        P (원본 점)
        │
        │ (수직)
        │
        ▼
    ────P'────────────  직선
           ↑
         투영된 점

    [투영 공식]
    직선: L(t) = point + t * direction
    점 P의 투영점 P':
        t = (P - point) · direction
        P' = point + t * direction

    [preserve_order 옵션]
    True:  원본 순서 유지 (시간 순서 보존)
    False: 직선을 따라 정렬 (위치 순서)

    Args:
        points: [N, 3] 좌표 배열
        line: 직선 파라미터 (fit_line_pca의 결과)
        preserve_order: True면 원본 순서 유지, False면 직선따라 정렬

    Returns:
        투영된 좌표 배열 [N, 3]

    Examples:
        >>> points = np.array([[0,1,0], [1,0.5,0], [2,1.5,0]])
        >>> line = fit_line_pca(points)
        >>> projected = project_points_to_line(points, line)
        >>> # projected는 모두 직선 위에 있음
    """
    projected = np.zeros_like(points)

    for i, p in enumerate(points):
        # 점에서 직선의 기준점까지의 벡터
        v = p - line.point

        # 방향 벡터에 투영 (내적)
        # t = v · d  (d는 정규화된 방향 벡터)
        t = np.dot(v, line.direction)

        # 직선 위의 점 계산
        projected[i] = line.point + t * line.direction

    if not preserve_order:
        # 직선 방향으로 정렬
        # 각 점의 t값(직선 위 위치)으로 정렬
        t_values = np.array([np.dot(p - line.point, line.direction) for p in points])
        sort_idx = np.argsort(t_values)
        projected = projected[sort_idx]

    return projected


# =============================================================================
# 직선 구간 감지
# =============================================================================

def detect_straight_segments(
    positions: np.ndarray,
    min_length: int = DEFAULT_MIN_SEGMENT_LENGTH,
    angle_threshold: float = DEFAULT_ANGLE_THRESHOLD
) -> List[Tuple[int, int]]:
    """
    경로에서 직선 구간들을 자동으로 감지합니다.

    [감지 방법]
    1. 이동 방향의 변화 각도를 계산
    2. 각도 변화가 threshold 이상이면 새 구간 시작
    3. 최소 길이 이상인 구간만 반환

    [시각화]
                     B
                    /
    A──────────────•      ← 여기서 방향 급변 (45도 이상)
                    \\
                     C────────D

    결과: [(A구간 시작, A구간 끝), (C구간 시작, D구간 끝)]

    Args:
        positions: [N, 3] 좌표 배열
        min_length: 최소 구간 길이 (포인트 수)
        angle_threshold: 구간 분리 각도 (도)

    Returns:
        (시작 인덱스, 끝 인덱스) 튜플 리스트
    """
    if len(positions) < min_length:
        return [(0, len(positions) - 1)]

    segments = []
    segment_start = 0

    for i in range(1, len(positions) - 1):
        # 이전 방향
        v1 = positions[i] - positions[i - 1]
        # 다음 방향
        v2 = positions[i + 1] - positions[i]

        # 방향 변화 각도 계산
        angle = _angle_between_vectors(v1, v2)

        # 큰 방향 변화 감지 → 구간 분리
        if angle > angle_threshold:
            if i - segment_start >= min_length:
                segments.append((segment_start, i))
            segment_start = i

    # 마지막 구간 추가
    if len(positions) - 1 - segment_start >= min_length:
        segments.append((segment_start, len(positions) - 1))

    # 구간이 없으면 전체를 하나의 구간으로
    if not segments:
        segments = [(0, len(positions) - 1)]

    return segments


def _angle_between_vectors(v1: np.ndarray, v2: np.ndarray) -> float:
    """
    두 벡터 사이의 각도를 계산합니다 (도 단위).

    Args:
        v1: 첫 번째 벡터
        v2: 두 번째 벡터

    Returns:
        각도 (도)
    """
    len1 = np.linalg.norm(v1)
    len2 = np.linalg.norm(v2)

    if len1 < 1e-10 or len2 < 1e-10:
        return 0.0

    cos_angle = np.dot(v1, v2) / (len1 * len2)
    cos_angle = np.clip(cos_angle, -1.0, 1.0)

    return np.degrees(np.arccos(cos_angle))


# =============================================================================
# 메인 직선화 함수
# =============================================================================

def straighten_path(
    positions: np.ndarray,
    segment_length: int = DEFAULT_SEGMENT_LENGTH,
    angle_threshold: float = DEFAULT_ANGLE_THRESHOLD,
    linearity_threshold: float = DEFAULT_LINEARITY_THRESHOLD,
    min_segment_length: int = DEFAULT_MIN_SEGMENT_LENGTH,
    max_displacement: float = DEFAULT_MAX_DISPLACEMENT
) -> Tuple[np.ndarray, Dict]:
    """
    전체 경로를 직선화합니다.

    [처리 과정]
    1. 경로를 여러 구간으로 분할 (방향 변화 기준)
    2. 각 구간에 대해:
       a. PCA로 직선 피팅
       b. 직선성 검사 (linearity_threshold)
       c. 최대 변위 검사 (max_displacement)
       d. 점들을 직선 위로 투영
    3. 모든 구간을 연결

    [시각화]
    입력 경로:
    ~~~∿∿~~~∿∿~~~│~~~∿∿~~~∿∿~~~│~~~∿∿~~~
       구간 1        구간 2        구간 3

    출력 경로:
    ─────────────│─────────────│─────────
       직선 1        직선 2        직선 3

    [segment_length vs angle_threshold]
    - segment_length: 고정 길이로 구간 분할 (단순)
    - angle_threshold: 방향 변화 기준 분할 (더 자연스러움, 이 함수에서 사용)

    [max_displacement]
    투영 후 어떤 점이든 원본에서 max_displacement 이상 이동하면
    해당 구간은 직선화하지 않습니다. 이를 통해 L자형 경로가
    대각선으로 왜곡되는 것을 방지합니다.

    Args:
        positions: [N, 3] 좌표 배열
        segment_length: 참고용 (실제로는 angle_threshold로 분할)
        angle_threshold: 구간 분리 각도 (도)
        linearity_threshold: 직선 피팅 품질 임계값 (0~1)
        min_segment_length: 최소 구간 길이
        max_displacement: 최대 허용 변위 (미터). 이 값 이상 이동하면 skip

    Returns:
        (직선화된 좌표 배열, 처리 통계)

    Examples:
        >>> # 흔들린 직선 경로
        >>> noisy = np.array([[i, np.sin(i*0.5)*0.1, 0] for i in range(50)])
        >>> straight, stats = straighten_path(noisy)
        >>> print(f"직선화된 구간: {stats['straightened_segments']}")
    """
    if len(positions) < min_segment_length:
        return positions.copy(), {'straightened_segments': 0, 'skipped_segments': 0}

    # Step 1: 구간 분할
    segments = detect_straight_segments(positions, min_segment_length, angle_threshold)

    # Step 2: 각 구간을 재귀적으로 직선화
    result = positions.copy()
    stats = {
        'total_segments': len(segments),
        'straightened_segments': 0,
        'skipped_segments': 0,
        'merged_segments': 0,
        'segment_details': []
    }

    sub_segment_bounds = []  # 직선화된 sub-segment 범위 추적

    for start_idx, end_idx in segments:
        _straighten_segment_recursive(
            positions, result, start_idx, end_idx,
            max_displacement, min_segment_length,
            stats, sub_segment_bounds
        )

    # Step 3: 거의 평행한 인접 sub-segment 병합
    #   ex) 60m 복도가 4개 sub-segment로 나뉘었는데
    #       방향이 비슷하면 하나로 합쳐서 반듯한 직선 생성
    merged = _merge_collinear_segments(
        result, sub_segment_bounds, max_displacement
    )
    stats['merged_segments'] = merged

    return result, stats


def _straighten_segment_recursive(
    positions: np.ndarray,
    result: np.ndarray,
    start_idx: int,
    end_idx: int,
    max_displacement: float,
    min_segment_length: int,
    stats: Dict,
    sub_segment_bounds: List,
    depth: int = 0,
    max_depth: int = 4
):
    """
    세그먼트 직선화를 시도하고, 실패 시 최대 편차 지점에서
    분할하여 재시도합니다.

    [핵심 개선점]
    1. 분할 지점 = 최대 편차 지점 (코너를 정확히 찾음)
       기존 중간점 분할 → L자 코너가 대각선으로 왜곡됨
       최대 편차 분할  → L자 코너에서 정확히 분리

        PCA 직선 (대각선)
        ──────────────────
               ↑ 최대 편차 = 코너!
        A ────● B (코너)
              │
              C

    2. max_displacement만으로 판정 (linearity 불필요)
       편차 2m 이내 → 직선화 OK (약간의 곡선도 흡수)

    Args:
        positions: 원본 좌표 배열
        result: 결과 배열 (in-place 수정)
        start_idx, end_idx: 세그먼트 범위
        max_displacement: 최대 허용 편차 (m)
        sub_segment_bounds: 직선화된 구간 범위 리스트 (추적용)
        depth: 현재 재귀 깊이
        max_depth: 최대 재귀 깊이
    """
    segment_points = positions[start_idx:end_idx + 1]
    n_points = len(segment_points)

    # 최소 포인트 수 (깊이에 따라 완화)
    min_pts = max(3, min_segment_length // (2 ** max(depth, 1)))
    if n_points < min_pts:
        return

    # PCA 직선 피팅 + 투영
    line = fit_line_pca(segment_points)
    projected = project_points_to_line(segment_points, line, preserve_order=True)

    # 편차 계산
    displacements = np.linalg.norm(projected - segment_points, axis=1)
    max_disp = float(np.max(displacements))

    if max_disp <= max_displacement:
        # 편차가 허용 범위 내 → 직선화!
        result[start_idx:end_idx + 1] = projected
        stats['straightened_segments'] += 1
        sub_segment_bounds.append((start_idx, end_idx))
        return

    # 편차 초과 → 최대 편차 지점에서 분할
    if depth < max_depth and n_points >= 2 * min_pts:
        # 코너 찾기: 가장자리 제외하고 최대 편차 지점 탐색
        edge_margin = min_pts
        if n_points > 2 * edge_margin:
            interior = displacements[edge_margin:n_points - edge_margin]
            split_local = edge_margin + int(np.argmax(interior))
        else:
            split_local = n_points // 2

        split_idx = start_idx + split_local

        _straighten_segment_recursive(
            positions, result, start_idx, split_idx,
            max_displacement, min_segment_length,
            stats, sub_segment_bounds, depth + 1, max_depth
        )
        _straighten_segment_recursive(
            positions, result, split_idx, end_idx,
            max_displacement, min_segment_length,
            stats, sub_segment_bounds, depth + 1, max_depth
        )
    else:
        # 더 이상 분할 불가 → 작은 세그먼트라도 강제 직선화
        # (3~5점 정도의 짧은 구간이므로 강제 투영해도 시각적 영향 미미)
        if n_points >= 3:
            line = fit_line_pca(segment_points)
            projected = project_points_to_line(segment_points, line, preserve_order=True)
            result[start_idx:end_idx + 1] = projected
            stats['straightened_segments'] += 1
            sub_segment_bounds.append((start_idx, end_idx))
        else:
            stats['skipped_segments'] += 1


def _merge_collinear_segments(
    result: np.ndarray,
    bounds: List[Tuple[int, int]],
    max_displacement: float,
    merge_angle: float = 30.0
) -> int:
    """
    거의 평행한 인접 직선 세그먼트를 하나로 병합합니다.

    [문제 상황]
    60m 복도가 재귀 분할로 4개 sub-segment로 나뉘면,
    각각 미세하게 다른 PCA 방향을 가져서 전체가 약간 삐뚤어짐:

        ──── ──── ──── ────    (4개 sub-segment, 각도 미세 차이)
              ↓ 병합
        ────────────────────   (1개 직선)

    [병합 조건]
    1. 인접해 있어야 함 (end1 == start2 또는 1점 이내)
    2. 방향 각도 차이 < merge_angle (30°)
    3. 병합된 결과의 max_displacement가 허용 범위 내

    Args:
        result: 직선화된 좌표 배열 (in-place 수정)
        bounds: 직선화된 sub-segment 범위 리스트
        max_displacement: 최대 허용 편차 (m)
        merge_angle: 병합 허용 각도 차이 (도)

    Returns:
        병합된 횟수
    """
    if len(bounds) < 2:
        return 0

    bounds.sort(key=lambda x: x[0])

    merged_count = 0
    i = 0
    while i < len(bounds) - 1:
        start1, end1 = bounds[i]
        start2, end2 = bounds[i + 1]

        # 인접 확인 (1점 이내 차이)
        if start2 - end1 > 1:
            i += 1
            continue

        # 방향 각도 비교
        dir1 = result[end1] - result[start1]
        dir2 = result[end2] - result[start2]
        angle = _angle_between_vectors(dir1, dir2)

        if angle > merge_angle:
            i += 1
            continue

        # 병합 시도: 두 구간을 하나의 직선으로
        merged_points = result[start1:end2 + 1]
        line = fit_line_pca(merged_points)
        projected = project_points_to_line(merged_points, line, preserve_order=True)

        max_disp = float(np.max(np.linalg.norm(projected - merged_points, axis=1)))

        if max_disp <= max_displacement:
            # 병합 성공
            result[start1:end2 + 1] = projected
            bounds[i] = (start1, end2)
            bounds.pop(i + 1)
            merged_count += 1
            # i 유지 → 다음 세그먼트와도 병합 시도
        else:
            i += 1

    return merged_count


# =============================================================================
# 고정 길이 구간 직선화 (대안 방법)
# =============================================================================

def straighten_path_fixed_segments(
    positions: np.ndarray,
    segment_length: int = DEFAULT_SEGMENT_LENGTH,
    linearity_threshold: float = DEFAULT_LINEARITY_THRESHOLD
) -> Tuple[np.ndarray, Dict]:
    """
    고정 길이 구간으로 나누어 직선화합니다.

    [사용 상황]
    - 방향 변화가 점진적인 경우
    - 일정한 간격으로 직선화하고 싶을 때

    [주의사항]
    곡선 구간이 중간에서 잘릴 수 있어서
    detect_straight_segments()를 사용한 방법보다
    자연스럽지 않을 수 있습니다.

    Args:
        positions: [N, 3] 좌표 배열
        segment_length: 구간 길이 (포인트 수)
        linearity_threshold: 직선 피팅 품질 임계값

    Returns:
        (직선화된 좌표 배열, 처리 통계)
    """
    if len(positions) < segment_length:
        return positions.copy(), {'straightened_segments': 0}

    result = positions.copy()
    stats = {
        'total_segments': 0,
        'straightened_segments': 0,
        'skipped_segments': 0
    }

    # 고정 길이로 구간 분할
    for start_idx in range(0, len(positions), segment_length):
        end_idx = min(start_idx + segment_length, len(positions))
        segment_points = positions[start_idx:end_idx]

        stats['total_segments'] += 1

        if len(segment_points) < 3:
            continue

        # PCA 직선 피팅
        line = fit_line_pca(segment_points)

        if line.explained_variance_ratio >= linearity_threshold:
            projected = project_points_to_line(segment_points, line, preserve_order=True)
            result[start_idx:end_idx] = projected
            stats['straightened_segments'] += 1
        else:
            stats['skipped_segments'] += 1

    return result, stats


# =============================================================================
# XY 평면만 직선화 (Z 유지)
# =============================================================================

def straighten_path_xy_only(
    positions: np.ndarray,
    segment_length: int = DEFAULT_SEGMENT_LENGTH,
    angle_threshold: float = DEFAULT_ANGLE_THRESHOLD,
    linearity_threshold: float = DEFAULT_LINEARITY_THRESHOLD
) -> Tuple[np.ndarray, Dict]:
    """
    XY 평면에서만 직선화하고 Z값은 원본을 유지합니다.

    [사용 상황]
    - 수평 이동은 직선화하되
    - 높이 변화(계단, 경사로)는 보존하고 싶을 때

    [처리 방법]
    1. XY 좌표만 추출하여 2D 직선 피팅
    2. XY를 직선 위로 투영
    3. Z는 원본 유지

    입력:                    출력:
    XY: ~~~∿∿~~~            XY: ──────────
    Z:  ────↗────            Z:  ────↗────  (보존)

    Args:
        positions: [N, 3] 좌표 배열
        segment_length: 참고용
        angle_threshold: 구간 분리 각도
        linearity_threshold: 직선 피팅 품질 임계값

    Returns:
        (직선화된 좌표 배열, 처리 통계)
    """
    if len(positions) < 3:
        return positions.copy(), {'straightened_segments': 0}

    # Z값 백업
    original_z = positions[:, 2].copy()

    # XY만으로 2D 좌표 생성 (Z=0)
    xy_positions = np.column_stack([positions[:, :2], np.zeros(len(positions))])

    # XY 평면에서 직선화
    straightened_xy, stats = straighten_path(
        xy_positions,
        segment_length=segment_length,
        angle_threshold=angle_threshold,
        linearity_threshold=linearity_threshold
    )

    # 결과: 직선화된 XY + 원본 Z
    result = np.column_stack([straightened_xy[:, :2], original_z])

    return result, stats


# =============================================================================
# 유틸리티 함수
# =============================================================================

def calculate_path_straightness(positions: np.ndarray) -> float:
    """
    경로의 직선성을 0~1 사이 값으로 계산합니다.

    [계산 방법]
    전체 경로에 PCA 적용하여 주성분이 설명하는 분산 비율 반환

    [해석]
    - 1.0: 완벽한 직선
    - 0.8~1.0: 거의 직선
    - 0.5~0.8: 약간 구부러짐
    - 0.5 미만: 많이 구부러진 경로

    Args:
        positions: [N, 3] 좌표 배열

    Returns:
        직선성 점수 (0~1)
    """
    if len(positions) < 3:
        return 1.0

    line = fit_line_pca(positions)
    return line.explained_variance_ratio


# =============================================================================
# RDP + 직선 스냅 (snap_to_lines)
# =============================================================================

def snap_to_lines(
    positions: np.ndarray,
    epsilon: float = 0.5,
    point_spacing: float = 0.5,
    gap_threshold: float = 5.0
) -> np.ndarray:
    """
    RDP로 꼭짓점을 추출하고, 꼭짓점 사이를 직선으로 연결합니다.

    [처리 과정]
    1. 갭(gap_threshold 이상)에서 세그먼트 분리
    2. 각 세그먼트에 RDP 적용 → 꼭짓점만 남김
    3. 꼭짓점 사이를 point_spacing 간격으로 직선 보간
    4. 세그먼트 재결합

    [시각화]
    입력:  A ~~~∿∿~~~∿∿~~~•~~~∿∿~~~∿∿~~→ B
                          ↓ RDP
    꼭짓점: A ─────────── C ─────────── B
                          ↓ 직선 보간
    출력:  A ──·──·──·── C ──·──·──·── B  (일정 간격 포인트)

    Args:
        positions: [N, 3] 좌표 배열
        epsilon: RDP 허용 오차 (미터). 클수록 더 단순화
        point_spacing: 직선 위 포인트 간격 (미터)
        gap_threshold: 세그먼트 분리 거리 (미터)

    Returns:
        직선 조합으로 구성된 좌표 배열
    """
    if len(positions) < 3:
        return positions.copy()

    # Step 1: 갭에서 세그먼트 분리
    segments = split_at_gaps(positions, gap_threshold)

    # Step 2-3: 각 세그먼트에 RDP + 직선 보간
    result_segments = []
    for seg in segments:
        if len(seg) < 2:
            result_segments.append(seg)
            continue

        # RDP로 핵심 꼭짓점 추출
        vertices = simplify_path_rdp(seg, epsilon=epsilon)

        if len(vertices) < 2:
            result_segments.append(vertices)
            continue

        # 꼭짓점 사이를 직선 보간
        interpolated = _interpolate_between_vertices(vertices, point_spacing)
        result_segments.append(interpolated)

    # Step 4: 세그먼트 재결합
    return np.concatenate(result_segments, axis=0)


def _interpolate_between_vertices(
    vertices: np.ndarray,
    spacing: float
) -> np.ndarray:
    """
    꼭짓점 사이를 일정 간격으로 직선 보간합니다.

    [동작]
    각 인접 꼭짓점 쌍(A→B)에 대해:
    - 구간 길이 계산
    - spacing 간격으로 포인트 배치
    - 마지막 꼭짓점은 항상 포함

    Args:
        vertices: [M, 3] 꼭짓점 배열
        spacing: 포인트 간격 (미터)

    Returns:
        보간된 좌표 배열
    """
    result = [vertices[0]]

    for i in range(len(vertices) - 1):
        start = vertices[i]
        end = vertices[i + 1]
        seg_length = np.linalg.norm(end - start)

        if seg_length < 1e-10:
            continue

        # 간격에 맞는 포인트 수 계산
        n_points = max(1, int(np.ceil(seg_length / spacing)))

        # np.linspace로 균등 보간 (시작점 제외, 끝점 포함)
        for j in range(1, n_points + 1):
            t = j / n_points
            point = start + t * (end - start)
            result.append(point)

    return np.array(result)


def calculate_deviation_from_line(positions: np.ndarray) -> Dict:
    """
    경로가 직선에서 얼마나 벗어나는지 계산합니다.

    Args:
        positions: [N, 3] 좌표 배열

    Returns:
        편차 통계 (mean, max, std)
    """
    if len(positions) < 3:
        return {'mean': 0.0, 'max': 0.0, 'std': 0.0}

    # 직선 피팅
    line = fit_line_pca(positions)

    # 각 점에서 직선까지의 거리 계산
    distances = []
    for p in positions:
        # 투영점 계산
        v = p - line.point
        t = np.dot(v, line.direction)
        projection = line.point + t * line.direction

        # 원본과 투영점 사이 거리
        dist = np.linalg.norm(p - projection)
        distances.append(dist)

    distances = np.array(distances)

    return {
        'mean': float(np.mean(distances)),
        'max': float(np.max(distances)),
        'std': float(np.std(distances)),
        'distances': distances.tolist()
    }
