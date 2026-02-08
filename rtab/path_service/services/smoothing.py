"""
=============================================================================
경로 스무딩 모듈 (Path Smoothing Module)
=============================================================================

이 모듈은 카메라 궤적의 노이즈를 제거하고 부드러운 경로를 생성합니다.

[문제 상황]
SLAM 데이터는 센서 노이즈로 인해 떨리는(jittery) 경로가 됩니다:
    실제 경로:  A ─────────────────→ B (직선)
    측정 경로:  A ~~∿∿~~∿∿~~∿∿~~∿→ B (노이즈)

[제공 기능]
1. smooth_path: 가우시안 필터 적용 (가장 일반적)
2. smooth_path_spline: B-스플라인 보간 (부드러운 곡선)
3. adaptive_smooth: 곡률 기반 적응형 스무딩
4. remove_outliers: 이상치 제거

[스무딩 방법 선택 가이드]
┌─────────────────────┬────────────────────┬───────────────────┐
│ 방법                │ 장점               │ 사용 케이스       │
├─────────────────────┼────────────────────┼───────────────────┤
│ gaussian (기본)     │ 빠름, 예측 가능    │ 일반적 노이즈     │
│ spline              │ 매끄러운 곡선      │ 고품질 시각화     │
│ adaptive            │ 곡선부 보존        │ 복잡한 경로       │
└─────────────────────┴────────────────────┴───────────────────┘

[사용 예시]
    from services.smoothing import smooth_path, remove_outliers

    # Step 1: 이상치 제거
    cleaned = remove_outliers(raw_positions, threshold_std=3.0)

    # Step 2: 가우시안 스무딩
    smoothed = smooth_path(cleaned, sigma=2.0)
"""

import numpy as np
from typing import Optional, List
from scipy.ndimage import gaussian_filter1d
from scipy.interpolate import splprep, splev


# =============================================================================
# 상수 정의
# =============================================================================

# 기본 가우시안 시그마 (표준편차)
# - 값이 클수록 더 부드럽게 (더 많은 노이즈 제거)
# - 값이 작을수록 원본에 가깝게 (디테일 보존)
DEFAULT_GAUSSIAN_SIGMA = 2.0

# 최소 스무딩 시그마 (적응형 스무딩에서 사용)
MIN_SIGMA = 0.5

# 이상치 판정 기준 (표준편차의 배수)
DEFAULT_OUTLIER_THRESHOLD = 3.0

# 갭 감지 임계값 (미터)
# - 연속 포인트 간 거리가 이 값을 초과하면 별도 세그먼트로 분리
DEFAULT_GAP_THRESHOLD = 5.0


# =============================================================================
# 갭 인식 스무딩
# =============================================================================

def split_at_gaps(
    positions: np.ndarray,
    gap_threshold: float = DEFAULT_GAP_THRESHOLD
) -> List[np.ndarray]:
    """
    연속 포인트 간 거리가 gap_threshold를 초과하는 지점에서 경로를 분리합니다.

    [문제 상황]
    왕복 구간 제거(merge_overlapping_segments) 등의 처리 후
    경로에 큰 공백이 생길 수 있습니다.

        처리 전: A → B → C → D → C → B → A → E
        처리 후: A → B → C → D ──(40m 갭)──→ E

    이 갭을 감지하여 세그먼트로 분리합니다:
        세그먼트 1: A → B → C → D
        세그먼트 2: E

    Args:
        positions: [N, 3] 좌표 배열
        gap_threshold: 갭 판정 거리 (미터)

    Returns:
        세그먼트 리스트 (각각 numpy 배열)
    """
    if len(positions) < 2:
        return [positions]

    dists = np.linalg.norm(np.diff(positions, axis=0), axis=1)
    split_indices = np.where(dists > gap_threshold)[0] + 1

    if len(split_indices) == 0:
        return [positions]

    segments = []
    prev = 0
    for idx in split_indices:
        if idx - prev >= 2:
            segments.append(positions[prev:idx])
        prev = idx
    if len(positions) - prev >= 2:
        segments.append(positions[prev:])

    return segments


def smooth_path_gapaware(
    positions: np.ndarray,
    sigma: float = DEFAULT_GAUSSIAN_SIGMA,
    gap_threshold: float = DEFAULT_GAP_THRESHOLD
) -> np.ndarray:
    """
    갭을 인식하여 각 세그먼트를 독립적으로 스무딩합니다.

    [동작 원리]
    1. 큰 갭(gap_threshold 초과)에서 경로를 세그먼트로 분리
    2. 각 세그먼트에 독립적으로 가우시안 스무딩 적용
    3. 세그먼트들을 다시 합쳐서 반환

    이렇게 하면 갭 양쪽의 포인트가 서로 영향을 주지 않아
    대각선 아티팩트가 방지됩니다.

    Args:
        positions: [N, 3] 좌표 배열
        sigma: 가우시안 표준편차
        gap_threshold: 갭 판정 거리 (미터)

    Returns:
        스무딩된 좌표 배열
    """
    segments = split_at_gaps(positions, gap_threshold)

    smoothed_segments = []
    for seg in segments:
        smoothed_segments.append(smooth_path(seg, sigma=sigma))

    return np.concatenate(smoothed_segments, axis=0)


# =============================================================================
# 가우시안 스무딩 (기본)
# =============================================================================

def smooth_path(
    positions: np.ndarray,
    sigma: float = DEFAULT_GAUSSIAN_SIGMA
) -> np.ndarray:
    """
    가우시안 필터로 경로를 스무딩합니다.

    [가우시안 필터 원리]
    각 점을 주변 점들의 가중 평균으로 대체합니다.
    가중치는 정규분포(가우시안)를 따릅니다.

    수식: smoothed[i] = Σ(weight[j] × original[i+j])

    가중치 분포 (sigma=2 예시):
         ▲
      0.4├     ███
      0.3├    █████
      0.2├   ███████
      0.1├  █████████
         └─────────────→
           -4 -2  0  2  4  (거리)

    [sigma 선택 가이드]
    - sigma = 1: 약한 스무딩 (디테일 보존)
    - sigma = 2: 일반적 (권장)
    - sigma = 3+: 강한 스무딩 (과도하면 경로 왜곡)

    Args:
        positions: [N, 3] 좌표 배열
        sigma: 가우시안 표준편차 (클수록 부드러움)

    Returns:
        스무딩된 좌표 배열

    Examples:
        >>> noisy = np.array([[0,0,0], [1,0.1,0], [2,-0.1,0], [3,0.05,0]])
        >>> smooth = smooth_path(noisy, sigma=1.0)
        >>> # Y값의 노이즈(±0.1)가 줄어듦
    """
    # 최소 포인트 검증 (가우시안 필터는 최소 3개 필요)
    if len(positions) < 3:
        return positions

    # 결과 배열 초기화
    smoothed = np.zeros_like(positions)

    # 각 좌표축(X, Y, Z)에 독립적으로 가우시안 필터 적용
    # - 1D 필터를 3번 적용 (각 축에 대해)
    smoothed[:, 0] = gaussian_filter1d(positions[:, 0], sigma=sigma)  # X
    smoothed[:, 1] = gaussian_filter1d(positions[:, 1], sigma=sigma)  # Y
    smoothed[:, 2] = gaussian_filter1d(positions[:, 2], sigma=sigma)  # Z

    return smoothed


# =============================================================================
# B-스플라인 스무딩
# =============================================================================

def smooth_path_spline(
    positions: np.ndarray,
    smoothing_factor: float = 0.0,
    num_points: Optional[int] = None
) -> np.ndarray:
    """
    B-스플라인 보간으로 경로를 스무딩합니다.

    [B-스플라인이란?]
    부드러운 곡선을 생성하는 수학적 방법입니다.
    포인트들을 정확히 통과하거나(보간), 근처를 지나가도록(근사) 설정 가능합니다.

    [가우시안 vs 스플라인]
    - 가우시안: 기존 점을 수정
    - 스플라인: 새로운 부드러운 곡선 생성 (점 수 변경 가능)

    [smoothing_factor 설명]
    - 0.0: 모든 원본 점을 정확히 통과 (보간)
    - 0.0~1.0: 원본에서 약간 벗어남 (근사)
    - 1.0+: 더 부드럽지만 원본에서 많이 벗어남

    [시각화]
    smoothing = 0:   A ──*──*──*── B  (원본 점 통과)
    smoothing = 0.5: A ─────────── B  (원본 근처 통과)

    Args:
        positions: [N, 3] 좌표 배열
        smoothing_factor: 스플라인 스무딩 계수 (0 = 보간)
        num_points: 출력 포인트 수 (None = 입력과 동일)

    Returns:
        스무딩된 좌표 배열
    """
    # 스플라인은 최소 4개 점 필요
    if len(positions) < 4:
        # 점이 부족하면 가우시안으로 폴백
        return smooth_path(positions)

    try:
        # Step 1: 스플라인 파라미터 계산
        # splprep: 3D 점들을 통과하는 스플라인 생성
        # k: 스플라인 차수 (3 = 3차 스플라인, 가장 부드러움)
        tck, u = splprep(
            [positions[:, 0], positions[:, 1], positions[:, 2]],
            s=smoothing_factor,
            k=min(3, len(positions) - 1)  # 점이 적으면 차수 낮춤
        )

        # Step 2: 출력 포인트 수 결정
        if num_points is None:
            num_points = len(positions)

        # Step 3: 스플라인 평가
        # u_new: 0~1 사이의 균등 분포 파라미터
        u_new = np.linspace(0, 1, num_points)

        # splev: 파라미터 값에서 스플라인 좌표 계산
        smoothed = np.array(splev(u_new, tck)).T  # [3, N] → [N, 3]

        return smoothed

    except Exception:
        # 스플라인 실패 시 가우시안으로 폴백
        return smooth_path(positions)


# =============================================================================
# 적응형 스무딩
# =============================================================================

def adaptive_smooth(
    positions: np.ndarray,
    base_sigma: float = 1.0,
    curvature_weight: float = 0.5
) -> np.ndarray:
    """
    곡률 기반 적응형 스무딩을 적용합니다.

    [아이디어]
    - 직선 구간: 강한 스무딩 (노이즈 제거)
    - 곡선 구간: 약한 스무딩 (곡선 형태 보존)

    [동작 원리]
    1. 각 포인트의 로컬 곡률 계산
    2. 곡률에 따라 스무딩 강도 조절
       - 곡률 높음 → sigma 작음 → 원본에 가깝게
       - 곡률 낮음 → sigma 큼 → 강하게 스무딩

    [시각화]
                 곡선 (약한 스무딩)
                  ╱╲
                 ╱  ╲
    ───────────╱    ╲─────────────
       직선 (강한 스무딩)    직선 (강한 스무딩)

    [파라미터]
    - base_sigma: 기본 스무딩 강도
    - curvature_weight: 곡률의 영향력 (0=무시, 1=최대)

    Args:
        positions: [N, 3] 좌표 배열
        base_sigma: 기본 스무딩 시그마
        curvature_weight: 곡률 가중치 (0~1)

    Returns:
        적응적으로 스무딩된 좌표 배열
    """
    if len(positions) < 5:
        return smooth_path(positions, sigma=base_sigma)

    # Step 1: 각 포인트의 곡률 계산
    curvature = calculate_curvature(positions)

    # Step 2: 곡률 정규화 (0~1 범위)
    max_curvature = np.max(curvature) if np.max(curvature) > 0 else 1
    normalized_curvature = curvature / max_curvature

    # Step 3: 적응형 시그마 계산
    # 공식: sigma = base_sigma × (1 - weight × curvature)
    # - 곡률 높으면 → sigma 작아짐 → 약한 스무딩
    # - 곡률 낮으면 → sigma 유지 → 강한 스무딩
    adaptive_sigma = base_sigma * (1 - curvature_weight * normalized_curvature)

    # Step 4: 각 포인트별 가변 스무딩 적용
    smoothed = np.zeros_like(positions)

    for i in range(len(positions)):
        # 최소 시그마 보장
        sigma = max(MIN_SIGMA, adaptive_sigma[i])

        # 윈도우 크기 결정 (3σ 규칙: 99.7% 데이터 포함)
        window = int(3 * sigma)

        # 윈도우 범위
        start = max(0, i - window)
        end = min(len(positions), i + window + 1)

        # 가우시안 가중치 계산
        indices = np.arange(start, end)
        weights = np.exp(-0.5 * ((indices - i) / sigma) ** 2)
        weights /= weights.sum()  # 정규화 (합 = 1)

        # 가중 평균으로 스무딩
        smoothed[i] = np.sum(
            positions[start:end] * weights[:, np.newaxis],
            axis=0
        )

    return smoothed


def calculate_curvature(positions: np.ndarray) -> np.ndarray:
    """
    각 포인트에서의 로컬 곡률을 계산합니다.

    [곡률이란?]
    경로가 얼마나 급하게 휘는지를 나타내는 값입니다.
    - 직선: 곡률 = 0
    - 급한 커브: 곡률 = 높음

    [계산 방법]
    연속된 세 점 (P0, P1, P2)을 사용:

    P0 ──v1──→ P1 ──v2──→ P2

    곡률 = |v1 × v2| / (|v1| × |v2|)

    - v1, v2: 연속 벡터
    - ×: 외적 (cross product)
    - ||: 노름 (길이)

    [기하학적 의미]
    - 외적의 크기 = 두 벡터가 이루는 평행사변형의 넓이
    - 정규화하면 sin(θ)에 비례 (θ = 두 벡터 사이 각도)

    Args:
        positions: [N, 3] 좌표 배열

    Returns:
        각 포인트의 곡률 배열 [N]
    """
    if len(positions) < 3:
        return np.zeros(len(positions))

    curvature = np.zeros(len(positions))

    for i in range(1, len(positions) - 1):
        # 세 연속 점
        p0 = positions[i - 1]
        p1 = positions[i]      # 현재 점
        p2 = positions[i + 1]

        # 연속 벡터
        v1 = p1 - p0  # 이전 → 현재
        v2 = p2 - p1  # 현재 → 다음

        # 외적 계산
        cross = np.cross(v1, v2)
        cross_magnitude = np.linalg.norm(cross)

        # 벡터 길이
        len1 = np.linalg.norm(v1)
        len2 = np.linalg.norm(v2)

        # 곡률 계산 (0으로 나누기 방지)
        if len1 > 1e-10 and len2 > 1e-10:
            curvature[i] = cross_magnitude / (len1 * len2)

    # 경계 값 처리 (첫 번째, 마지막 점)
    curvature[0] = curvature[1]
    curvature[-1] = curvature[-2]

    return curvature


# =============================================================================
# 이상치 제거
# =============================================================================

def remove_outliers(
    positions: np.ndarray,
    threshold_std: float = DEFAULT_OUTLIER_THRESHOLD
) -> np.ndarray:
    """
    이웃 점들에서 크게 벗어난 이상치(outlier)를 보간으로 대체합니다.

    [이상치란?]
    센서 오류, 순간적인 tracking 실패 등으로 발생하는 비정상적인 값입니다.

    정상:  A ─── B ─── C ─── D ─── E
    이상:  A ─── B ─── C         D ─── E
                       │↓
                       X  ← 이상치 (갑자기 튀는 점)

    [감지 방법]
    1. 연속 점 간의 거리 계산
    2. 거리의 평균(μ)과 표준편차(σ) 계산
    3. 거리 > μ + 3σ인 점을 이상치로 판정

    [처리 방법]
    이상치를 양쪽 이웃의 중간값으로 대체 (선형 보간)

    Args:
        positions: [N, 3] 좌표 배열
        threshold_std: 이상치 판정 기준 (표준편차의 배수)

    Returns:
        이상치가 보간된 좌표 배열

    Examples:
        >>> # 중간에 큰 점프가 있는 데이터
        >>> data = np.array([[0,0,0], [1,0,0], [100,0,0], [3,0,0], [4,0,0]])
        >>> cleaned = remove_outliers(data)
        >>> # [100,0,0]이 [2,0,0] 근처로 보간됨
    """
    if len(positions) < 5:
        return positions

    # 결과 배열 (원본 복사)
    result = positions.copy()

    # Step 1: 연속 점 간의 거리 계산
    distances = np.linalg.norm(np.diff(positions, axis=0), axis=1)

    # Step 2: 통계 계산
    mean_dist = np.mean(distances)
    std_dist = np.std(distances)

    # Step 3: 이상치 임계값 설정
    threshold = mean_dist + threshold_std * std_dist

    # Step 4: 이상치 감지 및 보간
    for i in range(1, len(positions) - 1):
        # 이전 점까지의 거리
        dist_prev = np.linalg.norm(positions[i] - positions[i - 1])
        # 다음 점까지의 거리
        dist_next = np.linalg.norm(positions[i + 1] - positions[i])

        # 양쪽 중 하나라도 임계값 초과면 이상치
        if dist_prev > threshold or dist_next > threshold:
            # 선형 보간: 이전 점과 다음 점의 중간값
            result[i] = (positions[i - 1] + positions[i + 1]) / 2

    return result


# =============================================================================
# 유틸리티 함수
# =============================================================================

def get_smoothing_stats(
    original: np.ndarray,
    smoothed: np.ndarray
) -> dict:
    """
    스무딩 전후의 통계를 계산합니다.

    Args:
        original: 원본 좌표 배열
        smoothed: 스무딩된 좌표 배열

    Returns:
        통계 정보 딕셔너리
    """
    # 원본과 스무딩 결과 사이의 차이
    differences = np.linalg.norm(original - smoothed, axis=1)

    return {
        'avg_displacement': float(np.mean(differences)),
        'max_displacement': float(np.max(differences)),
        'total_points': len(original),
        'path_length_original': _calculate_path_length(original),
        'path_length_smoothed': _calculate_path_length(smoothed)
    }


def _calculate_path_length(positions: np.ndarray) -> float:
    """경로의 총 길이를 계산합니다."""
    if len(positions) < 2:
        return 0.0

    distances = np.linalg.norm(np.diff(positions, axis=0), axis=1)
    return float(np.sum(distances))
