"""
=============================================================================
경로 중복 제거 모듈 (Path Deduplication Module)
=============================================================================

이 모듈은 카메라 이동 경로에서 중복되거나 겹치는 구간을 제거합니다.

[문제 상황]
SLAM으로 실내 맵핑 시, 사용자가 같은 경로를 왕복하거나 반복해서 걸을 수 있습니다.
이 경우 동일한 위치에 여러 포인트가 쌓이게 됩니다.

    예시: 복도를 왕복한 경우
    원본:  A → B → C → D → C → B → A → E
    처리 후: A → B → C → D → E

[제공 기능]
1. deduplicate_path: KD-Tree 기반 공간 클러스터링으로 중복 제거
2. merge_overlapping_segments: 왕복 구간 감지 및 병합
3. simplify_path_rdp: RDP 알고리즘으로 경로 단순화 (직선 구간 압축)

[사용 예시]
    from services.deduplication import deduplicate_path, simplify_path_rdp

    # Step 1: 공간적 중복 제거
    unique_path = deduplicate_path(raw_positions, distance_threshold=0.3)

    # Step 2: 직선 구간 단순화
    simplified = simplify_path_rdp(unique_path, epsilon=0.1)
"""

import numpy as np
from typing import List, Optional, Tuple
from scipy.spatial import cKDTree


# =============================================================================
# 상수 정의
# =============================================================================

# 기본 중복 판정 거리 (미터)
# - 30cm 이내의 점들은 같은 위치로 간주
DEFAULT_DISTANCE_THRESHOLD = 0.3

# 기본 RDP 허용 오차 (미터)
# - 직선에서 10cm 이내의 점은 생략 가능
DEFAULT_RDP_EPSILON = 0.1


# =============================================================================
# KD-Tree 기반 중복 제거
# =============================================================================

def deduplicate_path(
    positions: np.ndarray,
    distance_threshold: float = DEFAULT_DISTANCE_THRESHOLD
) -> np.ndarray:
    """
    KD-Tree를 사용하여 공간적으로 가까운 중복 포인트를 제거합니다.

    [알고리즘 설명]
    KD-Tree는 다차원 공간에서 최근접 이웃 검색을 효율적으로 수행하는 자료구조입니다.

    처리 흐름:
    1. 모든 포인트로 KD-Tree 구축 - O(N log N)
    2. 각 포인트에 대해 threshold 거리 내의 이웃 검색 - O(log N)
    3. 첫 번째로 방문한 포인트만 유지, 이웃들은 중복으로 표시

    [시간 복잡도]
    - 전체: O(N log N) - N은 포인트 수
    - 단순 이중 루프 O(N²) 대비 훨씬 효율적

    [파라미터 선택 가이드]
    - 0.1m: 매우 정밀 (거의 모든 점 유지)
    - 0.3m: 일반적 (권장)
    - 0.5m: 적극적 (점 수 크게 감소)

    Args:
        positions: [N, 3] 좌표 배열
        distance_threshold: 중복 판정 거리 (미터)

    Returns:
        중복이 제거된 좌표 배열

    Examples:
        >>> raw = np.array([[0,0,0], [0.1,0,0], [1,0,0], [1.1,0,0], [2,0,0]])
        >>> deduplicated = deduplicate_path(raw, distance_threshold=0.2)
        >>> len(deduplicated)  # 3 (0, 1, 2 근처에서 대표점만 남음)
    """
    # 최소 포인트 검증
    if len(positions) < 2:
        return positions

    # Step 1: KD-Tree 구축
    # cKDTree: C로 구현된 고속 KD-Tree (scipy)
    tree = cKDTree(positions)

    # Step 2: 유지할 포인트 마스크 초기화
    # True = 유지, False = 제거 (중복)
    keep_mask = np.ones(len(positions), dtype=bool)

    # Step 3: 이미 처리된 포인트 추적
    visited = set()

    # Step 4: 각 포인트 처리
    for i in range(len(positions)):
        # 이미 중복으로 처리된 경우 스킵
        if i in visited:
            continue

        # 현재 포인트 주변의 모든 이웃 검색
        # query_ball_point: 주어진 거리 내의 모든 포인트 인덱스 반환
        neighbors = tree.query_ball_point(positions[i], distance_threshold)

        # 현재 포인트(i)는 유지하고, 나머지 이웃은 중복으로 표시
        for j in neighbors:
            if j != i and j not in visited:
                keep_mask[j] = False  # 중복으로 표시
                visited.add(j)

        visited.add(i)

    # Step 5: 유지할 포인트만 추출
    unique_positions = positions[keep_mask]

    # 최소 품질 보장 (너무 많이 제거된 경우 원본 반환)
    if len(unique_positions) < 2:
        return positions

    return unique_positions


# =============================================================================
# 왕복 구간 병합
# =============================================================================

def merge_overlapping_segments(
    positions: np.ndarray,
    overlap_threshold: float = 0.5
) -> np.ndarray:
    """
    왕복 이동으로 인한 중복 경로 구간을 병합합니다.

    [문제 상황]
    사용자가 복도를 왕복할 때:
        갈 때:   A → B → C → D
        올 때:   D → C → B → A
        결합:    A → B → C → D → C → B → A

    이 함수는 "되돌아가는" 구간을 감지하여 제거합니다.

    [알고리즘]
    1. 경로를 따라 순회하며 각 포인트 처리
    2. 현재 위치가 이전에 방문한 위치와 가까우면 "재방문" 감지
    3. 재방문 감지 시, 새로운 방향으로 분기하는 지점을 찾음
    4. 중복 구간은 스킵하고 분기점부터 이어서 처리

    Args:
        positions: [N, 3] 좌표 배열
        overlap_threshold: 재방문 판정 거리 (미터)

    Returns:
        중복 구간이 병합된 좌표 배열
    """
    if len(positions) < 3:
        return positions

    # 결과 경로 (첫 포인트로 초기화)
    result = [positions[0]]
    i = 1

    while i < len(positions):
        current = positions[i]

        # 현재 위치가 이전 경로의 어딘가와 가까운지 확인
        revisit_idx = _find_revisit_point(result, current, overlap_threshold)

        # 재방문이 감지되었고, 충분히 이전 지점인 경우
        if revisit_idx is not None and revisit_idx < len(result) - 2:
            # 앞으로의 경로에서 분기점 찾기
            diverge_idx = _find_divergence_point(
                positions[i:],           # 앞으로 갈 경로
                result[revisit_idx:],    # 재방문 감지된 이전 경로 구간
                overlap_threshold
            )

            if diverge_idx is not None:
                # 중복 구간 스킵하고 분기점으로 점프
                i += diverge_idx
                continue

        # 일반 케이스: 현재 포인트 추가
        result.append(current)
        i += 1

    return np.array(result)


def _find_revisit_point(
    path: List[np.ndarray],
    point: np.ndarray,
    threshold: float
) -> Optional[int]:
    """
    현재 포인트가 이전 경로의 어느 지점을 재방문하는지 찾습니다.

    [역할]
    - 경로의 각 포인트와 현재 포인트의 거리 계산
    - threshold 이내의 가장 가까운 이전 포인트 인덱스 반환

    Args:
        path: 지금까지 구성된 경로 (리스트)
        point: 현재 검사할 포인트
        threshold: 재방문 판정 거리

    Returns:
        재방문 포인트의 인덱스, 없으면 None
    """
    # 마지막 포인트는 제외 (바로 직전 포인트와의 비교는 의미 없음)
    for i, p in enumerate(path[:-1]):
        distance = np.linalg.norm(np.array(p) - point)
        if distance < threshold:
            return i
    return None


def _find_divergence_point(
    forward_path: np.ndarray,
    backward_path: List[np.ndarray],
    threshold: float
) -> Optional[int]:
    """
    앞으로 갈 경로가 뒤로 가던 경로에서 분기하는 지점을 찾습니다.

    [역할]
    - forward_path의 각 포인트가 backward_path의 어떤 포인트와도
      threshold 이상 떨어지는 최초의 인덱스를 반환

    Args:
        forward_path: 앞으로 진행할 경로
        backward_path: 재방문으로 판정된 이전 경로 구간
        threshold: 분기 판정 거리

    Returns:
        분기 시작 인덱스, 없으면 None
    """
    backward_arr = np.array(backward_path)

    for i, point in enumerate(forward_path):
        # 현재 포인트에서 backward_path의 모든 포인트까지 거리 계산
        distances = np.linalg.norm(backward_arr - point, axis=1)

        # 모든 거리가 threshold보다 크면 = 새로운 영역으로 분기
        if np.min(distances) > threshold:
            return i

    return None


# =============================================================================
# RDP (Ramer-Douglas-Peucker) 알고리즘
# =============================================================================

def simplify_path_rdp(
    positions: np.ndarray,
    epsilon: float = DEFAULT_RDP_EPSILON
) -> np.ndarray:
    """
    RDP 알고리즘으로 경로를 단순화합니다 (직선 구간 압축).

    [알고리즘 설명 - Ramer-Douglas-Peucker]
    직선에 가까운 구간을 단순화하여 포인트 수를 줄입니다.

    원리:
    1. 시작점과 끝점을 잇는 직선을 그림
    2. 중간 포인트들 중 직선에서 가장 먼 점을 찾음
    3. 가장 먼 점의 거리가 epsilon 이하면 → 중간 점들 모두 제거
    4. epsilon 초과면 → 그 점을 기준으로 분할 후 재귀 처리

    [시각적 예시]
    원본:     A ----*---*---*---- B   (* = 직선에서 약간 벗어난 점들)
              |     |<-- d -->|        d < epsilon이면 제거
    단순화:   A ------------------- B

    [epsilon 선택 가이드]
    - 0.05m: 매우 정밀 (곡선 보존)
    - 0.1m: 일반적 (권장)
    - 0.2m: 적극적 (직선화)

    Args:
        positions: [N, 3] 좌표 배열
        epsilon: 허용 오차 (미터) - 직선에서 이 거리 이내면 제거

    Returns:
        단순화된 좌표 배열

    Examples:
        >>> # 거의 직선인 10개 점 → 2개로 단순화
        >>> line = np.array([[0,0,0], [1,0.01,0], [2,0,0], ..., [10,0,0]])
        >>> simplified = simplify_path_rdp(line, epsilon=0.1)
        >>> len(simplified)  # 2 (시작점, 끝점만 남음)
    """
    if len(positions) < 3:
        return positions

    # 재귀적으로 유지할 포인트 인덱스 계산
    indices = _rdp_recursive(positions, epsilon)

    # 계산된 인덱스의 포인트만 반환
    return positions[indices]


def _rdp_recursive(points: np.ndarray, epsilon: float) -> List[int]:
    """
    RDP 알고리즘의 재귀 구현부입니다.

    [재귀 구조]
    Base case: 포인트가 3개 미만이면 모두 유지
    Recursive case:
        1. 시작-끝 직선에서 가장 먼 점 찾기
        2. 거리 > epsilon: 그 점에서 분할 후 양쪽 재귀
        3. 거리 <= epsilon: 시작점과 끝점만 유지

    Args:
        points: 처리할 포인트 배열
        epsilon: 허용 오차

    Returns:
        유지할 포인트의 인덱스 리스트
    """
    # Base case
    if len(points) < 3:
        return list(range(len(points)))

    # 시작점과 끝점
    start = points[0]
    end = points[-1]

    # 시작-끝 직선 벡터
    line_vec = end - start
    line_len = np.linalg.norm(line_vec)

    # 시작점과 끝점이 거의 같은 위치인 경우
    if line_len < 1e-10:
        return [0, len(points) - 1]

    # 직선의 단위 벡터
    line_unit = line_vec / line_len

    # 가장 먼 점 찾기
    max_dist = 0
    max_idx = 0

    for i in range(1, len(points) - 1):
        # 점에서 직선까지의 수직 거리 계산
        dist = _point_to_line_distance(points[i], start, line_unit)

        if dist > max_dist:
            max_dist = dist
            max_idx = i

    # Recursive case
    if max_dist > epsilon:
        # 가장 먼 점을 기준으로 분할
        # 왼쪽: start ~ max_idx (포함)
        left_indices = _rdp_recursive(points[:max_idx + 1], epsilon)
        # 오른쪽: max_idx ~ end (max_idx부터 시작)
        right_indices = _rdp_recursive(points[max_idx:], epsilon)

        # 결과 병합 (max_idx가 중복되지 않도록)
        # left_indices[-1]과 right_indices[0]은 같은 점(max_idx)
        return left_indices[:-1] + [idx + max_idx for idx in right_indices]
    else:
        # 모든 중간 점이 epsilon 이내 → 시작점과 끝점만 유지
        return [0, len(points) - 1]


def _point_to_line_distance(
    point: np.ndarray,
    line_start: np.ndarray,
    line_unit: np.ndarray
) -> float:
    """
    점에서 직선까지의 수직 거리를 계산합니다.

    [수학적 원리]
    점 P에서 직선 AB까지의 거리:
    1. 벡터 AP를 직선 방향으로 투영
    2. 투영점 Q 계산
    3. 거리 = |PQ|

         P
         |
         | ← 수직 거리
         |
    A----Q--------B
         ↑
      투영점

    Args:
        point: 거리를 측정할 점
        line_start: 직선의 시작점 (A)
        line_unit: 직선의 단위 방향 벡터

    Returns:
        점에서 직선까지의 수직 거리
    """
    # 시작점에서 점까지의 벡터
    point_vec = point - line_start

    # 직선 방향으로의 투영 길이 (내적)
    projection_length = np.dot(point_vec, line_unit)

    # 투영점 좌표
    projection_point = line_start + projection_length * line_unit

    # 점에서 투영점까지의 거리 = 수직 거리
    return np.linalg.norm(point - projection_point)


# =============================================================================
# 유틸리티 함수
# =============================================================================

def get_deduplication_stats(
    original: np.ndarray,
    processed: np.ndarray
) -> dict:
    """
    중복 제거 전후의 통계를 계산합니다.

    Args:
        original: 원본 좌표 배열
        processed: 처리된 좌표 배열

    Returns:
        통계 정보 딕셔너리
    """
    original_count = len(original)
    processed_count = len(processed)
    reduction_rate = 1 - (processed_count / original_count) if original_count > 0 else 0

    return {
        'original_count': original_count,
        'processed_count': processed_count,
        'removed_count': original_count - processed_count,
        'reduction_rate': float(reduction_rate),
        'reduction_percent': f"{reduction_rate * 100:.1f}%"
    }
