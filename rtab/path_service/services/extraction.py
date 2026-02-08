"""
=============================================================================
RTAB-Map DB 궤적 추출 모듈 (Trajectory Extraction Module)
=============================================================================

이 모듈은 RTAB-Map에서 생성한 SQLite DB 파일에서 카메라 이동 궤적을 추출합니다.

[RTAB-Map DB 구조]
- Node 테이블: 카메라가 촬영한 각 프레임의 정보
  - id: 노드 고유 ID
  - pose: 48바이트 바이너리 (3x4 변환 행렬)

[Pose 데이터 형식]
- 48바이트 = 12개의 float (각 4바이트)
- 3x4 행렬로 변환됨 (row-major order)

    [r11 r12 r13 | tx]
    [r21 r22 r23 | ty]  →  회전 행렬(3x3) + 이동 벡터(3x1)
    [r31 r32 r33 | tz]

- 마지막 열 (tx, ty, tz)이 카메라의 3D 위치 좌표

[사용 예시]
    from services.extraction import extract_trajectory_from_db

    positions, node_ids = extract_trajectory_from_db("path/to/map.db")
    # positions: numpy 배열 [N, 3] - 각 행이 (x, y, z) 좌표
    # node_ids: 해당 위치의 원본 노드 ID 리스트
"""

import sqlite3
import struct
import numpy as np
from typing import Tuple, List, Optional


# =============================================================================
# 상수 정의
# =============================================================================

# Pose 바이너리 데이터 크기 (12개 float × 4바이트 = 48바이트)
POSE_BLOB_SIZE = 48
FLOATS_PER_POSE = 12
POSE_MATRIX_SHAPE = (3, 4)


# =============================================================================
# Pose 추출 함수들
# =============================================================================

def extract_pose(pose_blob: bytes) -> Optional[np.ndarray]:
    """
    바이너리 blob에서 3x4 변환 행렬을 추출합니다.

    [동작 원리]
    1. 48바이트 blob을 12개의 float로 언패킹
    2. 12개 float를 3x4 행렬로 재배열 (row-major)

    Args:
        pose_blob: RTAB-Map DB에서 가져온 pose 바이너리 데이터

    Returns:
        3x4 numpy 배열 (변환 행렬), 실패 시 None

    Examples:
        >>> blob = cursor.execute("SELECT pose FROM Node WHERE id=1").fetchone()[0]
        >>> matrix = extract_pose(blob)
        >>> print(matrix.shape)  # (3, 4)
    """
    # 빈 데이터 체크
    if pose_blob is None or len(pose_blob) == 0:
        return None

    # 크기 검증 (48바이트 필수)
    if len(pose_blob) != POSE_BLOB_SIZE:
        return None

    try:
        # struct.unpack: 바이너리 → Python 숫자
        # '12f': 12개의 float (little-endian)
        values = struct.unpack('12f', pose_blob)

        # 1차원 배열 → 3x4 행렬
        matrix = np.array(values).reshape(POSE_MATRIX_SHAPE)

        return matrix

    except struct.error:
        # 언패킹 실패 (잘못된 데이터 형식)
        return None


def get_position_from_matrix(matrix: np.ndarray) -> Optional[np.ndarray]:
    """
    변환 행렬에서 위치 벡터(x, y, z)를 추출합니다.

    [변환 행렬 구조]
    3x4 행렬의 마지막 열(4번째 열)이 이동 벡터(translation)입니다.

        [r11 r12 r13 | tx]
        [r21 r22 r23 | ty]  →  위치 = [tx, ty, tz]
        [r31 r32 r33 | tz]

    Args:
        matrix: 3x4 변환 행렬

    Returns:
        위치 벡터 [x, y, z], 실패 시 None
    """
    if matrix is None:
        return None

    # 마지막 열(인덱스 3)이 이동 벡터
    return matrix[:, 3]


def is_valid_position(position: np.ndarray) -> bool:
    """
    유효한 위치인지 검증합니다.

    [무효 케이스]
    - None 값
    - 원점 (0, 0, 0) - 초기화 전 또는 실패한 pose
    - NaN 또는 무한대 값

    Args:
        position: 검증할 위치 벡터

    Returns:
        유효하면 True, 아니면 False
    """
    if position is None:
        return False

    # NaN 또는 무한대 체크
    if not np.isfinite(position).all():
        return False

    # 원점 체크 (매우 작은 값도 무효로 처리)
    if np.allclose(position, [0, 0, 0], atol=1e-6):
        return False

    return True


# =============================================================================
# DB에서 궤적 추출
# =============================================================================

def extract_trajectory_from_db(db_path: str) -> Tuple[np.ndarray, List[int]]:
    """
    RTAB-Map DB에서 전체 카메라 이동 궤적을 추출합니다.

    [처리 흐름]
    1. SQLite DB 연결
    2. Node 테이블에서 모든 pose 조회 (id 순서)
    3. 각 pose blob → 3x4 행렬 → (x, y, z) 위치 추출
    4. 유효한 위치만 필터링

    [반환 데이터]
    - positions: [N, 3] numpy 배열
      - N = 유효한 노드 수
      - 각 행 = (x, y, z) 3D 좌표
    - node_ids: 해당 위치의 원본 노드 ID 리스트

    Args:
        db_path: RTAB-Map .db 파일 경로

    Returns:
        (positions, node_ids) 튜플

    Raises:
        ValueError: 유효한 pose가 하나도 없을 때
        sqlite3.Error: DB 접근 실패 시

    Examples:
        >>> positions, node_ids = extract_trajectory_from_db("indoor_map.db")
        >>> print(f"총 {len(positions)}개 위치 추출됨")
        >>> print(f"X 범위: {positions[:, 0].min():.2f} ~ {positions[:, 0].max():.2f}")
    """
    # DB 연결
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()

    try:
        # 모든 노드의 pose 조회 (id 순서대로 = 시간 순서)
        cursor.execute('SELECT id, pose FROM Node ORDER BY id')
        rows = cursor.fetchall()

        # 결과 저장용 리스트
        positions = []
        node_ids = []

        # 각 노드 처리
        for node_id, pose_blob in rows:
            # Step 1: blob → 변환 행렬
            matrix = extract_pose(pose_blob)
            if matrix is None:
                continue

            # Step 2: 행렬 → 위치 벡터
            position = get_position_from_matrix(matrix)

            # Step 3: 유효성 검증
            if is_valid_position(position):
                positions.append(position)
                node_ids.append(node_id)

        # 결과 검증
        if len(positions) == 0:
            raise ValueError(
                f"DB에서 유효한 pose를 찾을 수 없습니다: {db_path}\n"
                f"총 {len(rows)}개 노드 중 유효한 pose가 0개입니다."
            )

        # 리스트 → numpy 배열
        return np.array(positions), node_ids

    finally:
        # DB 연결 종료 (예외 발생해도 반드시 실행)
        conn.close()


# =============================================================================
# 궤적 통계 함수
# =============================================================================

def get_trajectory_stats(positions: np.ndarray) -> dict:
    """
    궤적의 기본 통계 정보를 계산합니다.

    [계산 항목]
    - total_nodes: 총 노드(포인트) 수
    - x_range, y_range, z_range: 각 축의 (최소값, 최대값)
    - total_distance: 궤적의 총 이동 거리 (m)

    [거리 계산]
    연속된 두 점 사이의 유클리드 거리를 모두 합산:
    d = √[(x₂-x₁)² + (y₂-y₁)² + (z₂-z₁)²]

    Args:
        positions: [N, 3] 좌표 배열

    Returns:
        통계 정보 딕셔너리

    Examples:
        >>> stats = get_trajectory_stats(positions)
        >>> print(f"총 이동 거리: {stats['total_distance']:.2f}m")
        >>> print(f"높이 범위: {stats['z_range'][0]:.2f} ~ {stats['z_range'][1]:.2f}m")
    """
    # 각 축 분리
    x = positions[:, 0]
    y = positions[:, 1]
    z = positions[:, 2]

    # 연속된 점 간의 거리 계산
    # np.diff: 배열의 연속된 원소 간 차이 계산
    dx = np.diff(x)  # x₂-x₁, x₃-x₂, ...
    dy = np.diff(y)
    dz = np.diff(z)

    # 각 구간의 3D 거리
    segment_distances = np.sqrt(dx**2 + dy**2 + dz**2)

    # 총 이동 거리
    total_distance = np.sum(segment_distances)

    return {
        'total_nodes': len(positions),
        'x_range': (float(x.min()), float(x.max())),
        'y_range': (float(y.min()), float(y.max())),
        'z_range': (float(z.min()), float(z.max())),
        'total_distance': float(total_distance),
        'avg_step_distance': float(np.mean(segment_distances)) if len(segment_distances) > 0 else 0
    }


def get_pose_rotation(matrix: np.ndarray) -> Optional[np.ndarray]:
    """
    변환 행렬에서 회전 행렬(3x3)을 추출합니다.

    [용도]
    - 카메라가 바라보는 방향 계산
    - 이동 방향 추정

    Args:
        matrix: 3x4 변환 행렬

    Returns:
        3x3 회전 행렬, 실패 시 None
    """
    if matrix is None:
        return None

    # 처음 3열이 회전 행렬
    return matrix[:, :3]
