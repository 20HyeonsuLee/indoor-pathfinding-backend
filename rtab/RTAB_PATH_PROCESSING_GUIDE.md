# RTAB-Map 실내 경로 처리 시스템 학습 가이드

## 목차

1. [시스템 개요](#1-시스템-개요)
2. [프로젝트 구조](#2-프로젝트-구조)
3. [처리 파이프라인 전체 흐름](#3-처리-파이프라인-전체-흐름)
4. [Step 1: 궤적 추출 (extraction.py)](#4-step-1-궤적-추출-extractionpy)
5. [Step 2: 수직 통로 감지 (vertical_detector.py)](#5-step-2-수직-통로-감지-vertical_detectorpy)
6. [Step 3: 경로 직선화 (path_flattening.py)](#6-step-3-경로-직선화-path_flatteningpy)
7. [Step 4: 중복 제거 (deduplication.py)](#7-step-4-중복-제거-deduplicationpy)
8. [Step 5: 스무딩 (smoothing.py)](#8-step-5-스무딩-smoothingpy)
9. [Step 6: 그래프 구축 (junction_detection.py)](#9-step-6-그래프-구축-junction_detectionpy)
10. [Step 7: 시각화 (visualization.py)](#10-step-7-시각화-visualizationpy)
11. [FastAPI 서비스 (main.py)](#11-fastapi-서비스-mainpy)
12. [유틸리티 스크립트](#12-유틸리티-스크립트)
13. [주요 파라미터 총정리](#13-주요-파라미터-총정리)
14. [알고리즘 복잡도 정리](#14-알고리즘-복잡도-정리)

---

## 1. 시스템 개요

### 무엇을 하는 시스템인가?

SLAM 카메라(RTAB-Map)로 건물 내부를 촬영하면 `.db` 파일이 생성됩니다.
이 시스템은 그 `.db` 파일에서 **실내 길찾기용 경로 그래프**를 자동 추출합니다.

```
[사람이 카메라 들고 건물 내부 걸어다님]
        ↓
[RTAB-Map이 .db 파일 생성 (카메라 위치 기록)]
        ↓
[이 시스템이 .db → 경로 그래프 변환]
        ↓
[Spring Boot 서버가 그래프로 길찾기 제공]
```

### 핵심 문제: 왜 전처리가 필요한가?

카메라 궤적 원본 데이터에는 다음 문제가 있습니다:

| 문제 | 원인 | 해결 모듈 |
|------|------|-----------|
| 지그재그 흔들림 | 카메라 손떨림, 걸음걸이 | path_flattening.py |
| 왕복 중복 경로 | 같은 복도를 갔다 돌아옴 | deduplication.py |
| 센서 노이즈 | SLAM 정밀도 한계 | smoothing.py |
| 층 구분 없음 | 연속된 하나의 궤적 | vertical_detector.py |
| 그래프 없음 | 점 나열일 뿐 노드/엣지 없음 | junction_detection.py |

### 아키텍처

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│  Spring Boot    │────→│  Path Service   │────→│  응답 JSON      │
│  (메인 서버)      │     │  (FastAPI)      │     │  (노드+엣지)    │
│  Port: 8080     │     │  Port: 8001     │     │                 │
└─────────────────┘     └─────────────────┘     └─────────────────┘
        │                      │
        ▼                      ▼
  .db 파일 업로드        7단계 파이프라인 처리
```

---

## 2. 프로젝트 구조

```
rtab/
├── path_service/                     # 메인 서비스
│   ├── main.py                       # FastAPI 앱 + API 엔드포인트 (800줄)
│   ├── test_school.py                # school.db 테스트 스크립트 (173줄)
│   ├── requirements.txt              # 의존성 패키지
│   ├── Dockerfile                    # Docker 설정
│   └── services/                     # 처리 모듈들
│       ├── extraction.py             # DB → 3D 좌표 추출 (297줄)
│       ├── vertical_detector.py      # 계단 감지 + 층 분리 (577줄)
│       ├── path_flattening.py        # PCA 직선화 (652줄)
│       ├── deduplication.py          # 중복 제거 (448줄)
│       ├── smoothing.py              # 가우시안 스무딩 (532줄)
│       ├── junction_detection.py     # 갈림길 감지 + 그래프 (722줄)
│       └── visualization.py          # 미리보기 이미지 (299줄)
│
├── db/                               # DB 유틸리티
│   ├── school.db                     # RTAB-Map DB 파일 (89MB)
│   ├── extract_trajectory.py         # 궤적 추출 + 시각화 스크립트
│   └── merge_and_visualize.py        # 복수 스캔 비교 스크립트
│
├── convert_mesh.py                   # 포인트클라우드 → 3D 메쉬
├── convert_to_2d.py                  # 3D 메쉬 → 2D 평면도
└── point_merge.py                    # ICP 포인트클라우드 정합
```

**전체 파이썬 코드: 약 4,860줄**

### 의존성 패키지

```
fastapi==0.115.0        # 웹 프레임워크
uvicorn==0.30.0         # ASGI 서버
numpy==1.26.4           # 수치 연산
scipy==1.13.0           # 가우시안 필터, KD-Tree, 스플라인
matplotlib==3.9.0       # 시각화
pydantic==2.8.0         # 데이터 검증
python-multipart==0.0.9 # 파일 업로드
aiofiles==23.2.1        # 비동기 파일 I/O
```

---

## 3. 처리 파이프라인 전체 흐름

```
[RTAB-Map .db 파일]
        │
        ▼
┌──────────────────────────────────────────────────────┐
│  Step 1: 궤적 추출 (extraction.py)                    │
│  48바이트 pose blob → 3x4 변환행렬 → (x,y,z) 좌표    │
│  입력: .db 파일 / 출력: [N, 3] numpy 배열             │
└──────────────────────────────────────────────────────┘
        │
        ▼
┌──────────────────────────────────────────────────────┐
│  Step 2a: 수직 통로 감지 (vertical_detector.py)       │
│  슬라이딩 윈도우로 Z축 변화 분석 → 계단/엘리베이터     │
│  출력: stair_segments, stair_mask                     │
├──────────────────────────────────────────────────────┤
│  Step 2b: 층 분리 (vertical_detector.py)              │
│  Z값 히스토그램 클러스터링 → 층별 포인트 분류          │
│  출력: floors_data {층번호: {positions, node_ids}}     │
└──────────────────────────────────────────────────────┘
        │
        ▼
┌──────────────────────────────────────────────────────┐
│  Step 3: 경로 직선화 (path_flattening.py)             │
│  PCA로 주 이동 방향 계산 → 점을 직선 위로 투영         │
│  카메라 흔들림에 의한 지그재그 보정                     │
│  안전장치: max_displacement = 2.0m 초과 시 skip       │
└──────────────────────────────────────────────────────┘
        │
        ▼
┌──────────────────────────────────────────────────────┐
│  Step 4: 중복 제거 (deduplication.py)                 │
│  4a: 왕복 구간 병합 (merge_overlapping_segments)      │
│  4b: KD-Tree 기반 중복점 제거 (deduplicate_path)      │
│  4c: RDP 단순화 (simplify_path_rdp) [main.py만]      │
└──────────────────────────────────────────────────────┘
        │
        ▼
┌──────────────────────────────────────────────────────┐
│  Step 5: 스무딩 (smoothing.py)                        │
│  갭 인식 가우시안 스무딩 (smooth_path_gapaware)        │
│  5m 이상 갭에서 세그먼트 분리 후 각각 독립 스무딩      │
└──────────────────────────────────────────────────────┘
        │
        ▼
┌──────────────────────────────────────────────────────┐
│  Step 6: 그래프 구축 (junction_detection.py)           │
│  갈림길(45°이상 방향변화) 감지 → 노드/엣지 생성       │
│  노드 타입: ENDPOINT, JUNCTION, WAYPOINT, POI_CANDIDATE│
│  층간 연결: 계단/엘리베이터 엣지 추가                  │
└──────────────────────────────────────────────────────┘
        │
        ▼
┌──────────────────────────────────────────────────────┐
│  Step 7: 시각화 + JSON 응답                           │
│  미리보기 이미지 3종 (raw, processed, comparison)     │
│  JSON: floor_paths, vertical_passages, nodes, edges   │
└──────────────────────────────────────────────────────┘
```

### 실제 데이터 변화 (school.db 기준)

| 단계 | 포인트 수 | 비고 |
|------|-----------|------|
| 원본 추출 | 545개 | 전체 궤적 |
| 계단 제외 | 445개 | 100개 계단 포인트 분리 |
| 층 분리 | 0F:125, 1F:93, 4F:227 | 3개 층 |
| 직선화 | 동일 수 | 위치만 보정 |
| 중복 제거 | 0F:117, 1F:83, 4F:146 | 약 22% 제거 |
| 스무딩 | 동일 수 | 노이즈 제거 |
| **최종** | **346개** | 원본 대비 63% |

---

## 4. Step 1: 궤적 추출 (extraction.py)

### RTAB-Map DB 구조

RTAB-Map이 생성하는 SQLite DB의 핵심 테이블:

```sql
-- Node 테이블: 각 카메라 프레임의 위치
CREATE TABLE Node (
    id INTEGER PRIMARY KEY,
    pose BLOB,          -- 48바이트 (3x4 변환행렬)
    ...
);
```

### Pose Blob 구조 (48바이트)

카메라의 3D 위치와 방향을 나타내는 **3x4 변환행렬**이 48바이트 바이너리로 저장됩니다.

```
48 bytes = 12 floats × 4 bytes/float

┌─────────────────────────────────────┐
│  12개의 32비트 float (Little Endian) │
│  [r00, r01, r02, tx,                │
│   r10, r11, r12, ty,                │
│   r20, r21, r22, tz]                │
└─────────────────────────────────────┘

이것을 3x4 행렬로 재구성:

    ┌                          ┐
    │  r00  r01  r02  │  tx    │
T = │  r10  r11  r12  │  ty    │   ← 3x4 변환행렬
    │  r20  r21  r22  │  tz    │
    └                          ┘
         회전행렬 (R)    이동벡터 (t)
         3x3              3x1

- R (회전행렬): 카메라가 바라보는 방향
- t (이동벡터): 카메라의 3D 위치 → 이것이 우리가 추출하는 (x, y, z)
```

### 코드 핵심 로직

```python
import struct

POSE_BLOB_SIZE = 48  # 12 floats × 4 bytes

def extract_pose(blob: bytes) -> np.ndarray:
    """48바이트 blob → 3x4 변환행렬"""
    floats = struct.unpack('12f', blob)  # 12개 float 언패킹
    matrix = np.array(floats).reshape(3, 4)  # 3x4로 재구성
    return matrix

def get_position_from_matrix(matrix: np.ndarray) -> np.ndarray:
    """3x4 행렬에서 위치(x,y,z) 추출"""
    return matrix[:, 3]  # 마지막 열 = 이동벡터

# 전체 흐름
def extract_trajectory_from_db(db_path: str):
    conn = sqlite3.connect(db_path)
    cursor = conn.execute("SELECT id, pose FROM Node ORDER BY id")

    positions = []
    for node_id, pose_blob in cursor:
        if len(pose_blob) != 48:
            continue
        matrix = extract_pose(pose_blob)
        pos = get_position_from_matrix(matrix)

        # 유효성 검사: 원점이 아니고 NaN이 아닌 것만
        if np.allclose(pos, 0) or np.any(np.isnan(pos)):
            continue

        positions.append(pos)

    return np.array(positions), node_ids
```

### 핵심 개념: 변환행렬 (Transformation Matrix)

```
3D 컴퓨터 비전에서 카메라 위치를 표현하는 표준 방법:

    ┌              ┐   ┌     ┐     ┌     ┐
    │ R(3x3) t(3x1│ × │  P  │  =  │ P'  │
    │              │   │     │     │     │
    │ 0 0 0    1  │   │  1  │     │  1  │
    └              ┘   └     ┘     └     ┘
      4x4 변환행렬      월드좌표     카메라좌표

    실제로는 3x4만 저장 (마지막 행 [0,0,0,1]은 고정)

    t = (tx, ty, tz): 카메라의 월드 좌표 위치
    이것이 우리가 필요한 "경로의 점"입니다.
```

---

## 5. Step 2: 수직 통로 감지 (vertical_detector.py)

이 모듈은 두 가지 역할을 합니다:
1. **계단/엘리베이터 감지** (detect_stairs_first)
2. **층 분리** (separate_floors)

### 5.1 계단 감지 알고리즘

```
[핵심 아이디어]
높이(Z)가 급격히 변하는 구간 = 계단 or 엘리베이터

    Z(높이)
    │        ┌────── 4층 (12m)
    │       /
    │      / ← 계단 구간 (Z가 빠르게 변함)
    │     /
    │ ───┘    1층 (4m)
    │    / ← 계단
    │───┘     0층 (0m)
    └──────────────────── 포인트 인덱스
```

#### 슬라이딩 윈도우 분석

```python
WINDOW_SIZE = 10  # 10개 포인트씩 검사
Z_CHANGE_THRESHOLD = 0.05  # 포인트 간 최소 Z변화 (m)
MIN_TOTAL_Z_CHANGE = 1.5   # 한 구간의 최소 Z변화 (한 층 높이)

# 알고리즘
for i in range(len(positions) - WINDOW_SIZE):
    window = positions[i:i+WINDOW_SIZE]
    z_diffs = np.abs(np.diff(window[:, 2]))  # Z축 변화량
    total_z_change = np.sum(z_diffs)          # 윈도우 내 총 변화

    if total_z_change > MIN_TOTAL_Z_CHANGE:
        # 이 구간은 수직 이동!
        mark_as_vertical(i, i+WINDOW_SIZE)
```

```
예시: 10개 포인트 윈도우

    Z값: [0.0, 0.3, 0.7, 1.0, 1.4, 1.8, 2.1, 2.5, 2.8, 3.2]
    ΔZ:  [0.3, 0.4, 0.3, 0.4, 0.4, 0.3, 0.4, 0.3, 0.4]
    총 변화: 3.2m > 1.5m → 계단으로 판정!
```

#### 계단 vs 엘리베이터 구분

```python
ELEVATOR_XY_Z_RATIO = 1.0

# 수평 이동량 대비 수직 이동량 비교
xy_distance = sqrt(Δx² + Δy²)  # 수평 이동 거리
z_distance = abs(Δz)            # 수직 이동 거리
ratio = xy_distance / z_distance

if ratio < 1.0:
    type = "ELEVATOR"   # 수직 이동이 더 큼 (제자리에서 올라감)
else:
    type = "STAIRCASE"  # 수평+수직 동시 이동 (대각선으로 올라감)
```

```
엘리베이터: XY 이동 거의 없음     계단: XY 이동 + Z 이동
    │                                  /
    │                                 /
    │  (ratio < 1.0)                /  (ratio > 1.0)
    │                              /
    ▲                            ↗
```

### 5.2 층 분리 알고리즘

```
[핵심 아이디어]
같은 층의 포인트들은 비슷한 Z값을 가짐
Z값의 히스토그램에서 피크 = 각 층의 높이
```

#### Z값 히스토그램 클러스터링

```python
DEFAULT_FLOOR_HEIGHT = 3.0  # 표준 층 높이 (m)
MIN_POINTS_PER_FLOOR = 10   # 최소 포인트 수

def separate_floors(positions, node_ids, stair_mask):
    # 1. 계단 포인트 제외
    floor_points = positions[~stair_mask]

    # 2. Z값으로 히스토그램 생성
    z_values = floor_points[:, 2]
    hist, bin_edges = np.histogram(z_values, bins=50)

    # 3. 히스토그램 피크 찾기 (= 각 층의 높이)
    peaks = find_histogram_peaks(hist, bin_edges)
    # 예: peaks = [0.0, 4.2, 12.3] → 0층, 1층, 4층

    # 4. 각 포인트를 가장 가까운 피크(층)에 할당
    for point in floor_points:
        nearest_peak = argmin(|point.z - peaks|)
        assign_to_floor(point, nearest_peak)

    # 5. 층 번호 계산
    # floor_level = round(z_mean / floor_height)
    # z_mean=0 → 0층, z_mean=4.2 → 1층, z_mean=12.3 → 4층
```

```
히스토그램 시각화:

    빈도 │
      30 │  ██                            ← 0층 (Z ≈ 0m)
      25 │  ██
      20 │  ██  ██                        ← 1층 (Z ≈ 4m)
      15 │  ██  ██
      10 │  ██  ██                    ██  ← 4층 (Z ≈ 12m)
       5 │  ██  ██  ....  ....  ....  ██
       0 ┼──────────────────────────────── Z (m)
          0    4    6    8   10   12
```

---

## 6. Step 3: 경로 직선화 (path_flattening.py)

### 문제 상황

```
실제 복도 (직선):
A ─────────────────────────────→ B

카메라로 기록된 경로 (흔들림):
A ~~~∿∿~~~∿∿~~~∿∿~~~∿∿~~~∿∿~~→ B
  ↑ 손 떨림, 걸음걸이로 인한 좌우 흔들림
```

### PCA (주성분 분석) 직선 피팅

PCA는 데이터의 **분산이 가장 큰 방향**을 찾는 알고리즘입니다.
직선 경로에서는 **이동 방향이 분산이 가장 큰 방향**입니다.

```
원본 데이터:
    ∗   ∗
  ∗   ∗   ∗
    ∗   ∗
  ∗   ∗
────────────→  ← 주성분 방향 (PC1) = 이동 방향
  ∗
    ∗   ∗

분산이 큰 방향(가로) = 이동 방향 → 이것이 직선의 방향!
분산이 작은 방향(세로) = 노이즈 (카메라 흔들림)
```

#### 수학적 과정

```python
def fit_line_pca(points):
    # 1. 중심점 계산 (평균)
    center = np.mean(points, axis=0)  # 데이터의 무게중심

    # 2. 중심화 (평균을 빼서 원점으로 이동)
    centered = points - center

    # 3. 공분산 행렬 계산
    #    Cov = (1/n) × X^T × X
    #    3x3 행렬 (x,y,z 간의 상관관계)
    covariance = np.cov(centered.T)

    # 4. 고유값 분해
    #    공분산 행렬 = V × Λ × V^T
    #    고유벡터(V): 주성분 방향
    #    고유값(Λ):  각 방향의 분산 크기
    eigenvalues, eigenvectors = np.linalg.eigh(covariance)

    # 5. 가장 큰 고유값의 고유벡터 = 주성분 = 직선 방향
    max_idx = np.argmax(eigenvalues)
    direction = eigenvectors[:, max_idx]
    direction = direction / np.linalg.norm(direction)  # 정규화

    # 6. 직선성 지표 (explained variance ratio)
    #    = 최대 고유값 / 전체 고유값 합
    #    1.0 = 완벽한 직선, 0.5 = 원형에 가까움
    explained_ratio = eigenvalues[max_idx] / np.sum(eigenvalues)

    return LineParams(point=center, direction=direction,
                      explained_variance_ratio=explained_ratio)
```

```
고유값 분해 시각화:

    고유값 λ1 = 100 (큰 분산 → 이동 방향)
    고유값 λ2 = 2   (작은 분산 → 노이즈)
    고유값 λ3 = 0.5 (아주 작은 분산 → Z축 노이즈)

    explained_ratio = 100 / 102.5 = 0.976 → 97.6% 직선!

    λ2 방향 (노이즈)
    ↑
    │  ∗  ∗  ∗
    │ ∗  ∗  ∗  ∗  ∗
    ├──────────────→  λ1 방향 (이동 방향)
    │ ∗  ∗  ∗  ∗
    │  ∗  ∗  ∗
```

### 투영 (Projection)

점들을 찾은 직선 위로 "수직으로 떨어뜨림":

```python
def project_points_to_line(points, line):
    projected = np.zeros_like(points)

    for i, p in enumerate(points):
        # 점에서 직선 기준점까지의 벡터
        v = p - line.point

        # 방향벡터에 투영 (내적)
        t = np.dot(v, line.direction)

        # 직선 위의 점 계산
        projected[i] = line.point + t * line.direction

    return projected
```

```
투영 전:                     투영 후:
P1  ∗                        P1'
    │(수직)                   │
    ▼                         ▼
────●────────────  →   ─────●─────●─────●──── (직선)
         P2  ∗                    P2'
             │
             ▼
         ────●──────
```

### 구간 분할: 왜 전체를 한 번에 직선화하면 안 되나?

복도에는 꺾이는 지점이 있습니다. 전체를 한 직선으로 만들면 안 됩니다.

```
실제 복도 (L자형):
A ──────────┐
            │
            │
            └──────── B

전체를 PCA 하면?
A ─────────────────── B   ← 대각선으로 왜곡!

구간별로 PCA 하면:
A ──────────┐              ← 구간 1: 수평 직선
            │
            └──────── B    ← 구간 2: 수직 직선
```

#### 방향 변화 기반 구간 분할

```python
DEFAULT_ANGLE_THRESHOLD = 45.0  # 도

def detect_straight_segments(positions, min_length=10, angle_threshold=45.0):
    segments = []
    segment_start = 0

    for i in range(1, len(positions) - 1):
        # 연속 두 벡터 간의 각도
        v1 = positions[i] - positions[i-1]      # 이전 이동 방향
        v2 = positions[i+1] - positions[i]      # 다음 이동 방향

        angle = angle_between(v1, v2)

        if angle > 45.0:  # 방향이 45도 이상 변하면
            if i - segment_start >= min_length:  # 최소 길이 충족
                segments.append((segment_start, i))
            segment_start = i  # 새 구간 시작

    return segments
```

### 안전장치: 최대 변위 제한

PCA linearity가 높아도 (0.85 이상), L자형 경로의 대각선 투영은
점을 원래 위치에서 수~수십 미터 이동시킵니다. 이를 방지합니다.

```python
DEFAULT_MAX_DISPLACEMENT = 2.0  # 미터

# 투영 후 변위 검사
projected = project_points_to_line(segment_points, line)
displacements = np.linalg.norm(projected - segment_points, axis=1)
max_disp = np.max(displacements)

if max_disp > 2.0:
    # 이 구간은 직선화하지 않음 (L자형 경로일 가능성)
    skip_this_segment()
```

```
정상 구간:                    L자형 구간:
max displacement = 0.5m       max displacement = 16m!

 ∗∗∗∗∗∗∗∗∗∗∗∗∗               ∗∗∗∗∗∗
─────────────── (직선)              ∗∗∗∗∗∗
  ↕ 0.5m                      ↕ 16m!!
                           ─────────────── (대각선)
→ 직선화 OK!               → 직선화 SKIP!
```

### linearity (직선성) 해석

| explained_variance_ratio | 의미 | 판정 |
|--------------------------|------|------|
| 0.95 ~ 1.00 | 거의 완벽한 직선 | 직선화 |
| 0.85 ~ 0.95 | 직선에 가까움 (약간의 노이즈) | 직선화 |
| 0.70 ~ 0.85 | 곡선 성분 있음 | 원본 유지 |
| 0.70 미만 | 직선이라 보기 어려움 | 원본 유지 |

---

## 7. Step 4: 중복 제거 (deduplication.py)

### 7.1 왕복 구간 병합 (merge_overlapping_segments)

SLAM으로 건물을 촬영할 때 같은 복도를 갔다가 돌아오는 경우가 많습니다.

```
실제 이동:
A ──────→ B ──────→ C ──────→ B ──────→ D
  (복도1)   (복도2)   (되돌아옴) (새 복도)

문제: B→C→B 구간이 중복!

원하는 결과:
A ──────→ B ──────→ C
                │
                └──────→ D
```

#### 알고리즘

```python
def merge_overlapping_segments(positions, overlap_threshold=1.0):
    """
    1. 현재 위치가 이전에 지나간 곳과 가까운지 확인 (revisit)
    2. 되돌아가는 구간 감지
    3. 중복 구간 제거
    """
    result = [positions[0]]

    for i in range(1, len(positions)):
        current = positions[i]

        # 이미 방문한 곳에서 1m 이내인가?
        revisit_idx = find_revisit_point(current, result, threshold=1.0)

        if revisit_idx is not None:
            # 되돌아가는 구간 → 해당 지점부터 잘라내기
            result = result[:revisit_idx+1]

        result.append(current)

    return np.array(result)
```

### 7.2 KD-Tree 기반 중복점 제거 (deduplicate_path)

KD-Tree는 공간 검색에 최적화된 자료구조입니다.

```
[KD-Tree란?]
K차원 공간을 반복적으로 이등분하여 검색 효율을 O(N) → O(log N)으로 개선

예: 2D 공간

        x=5
    ┌────┼────┐
    │    │    │
  y=3    │  y=7
  ┌──┼──┐│┌──┼──┐
  │  │  │││  │  │
  A  B  C│D  E  F

"점 P에서 0.3m 이내의 점 찾기" → 전체 탐색 O(N) 대신 O(log N)
```

```python
from scipy.spatial import cKDTree

def deduplicate_path(positions, distance_threshold=0.3):
    tree = cKDTree(positions)      # KD-Tree 구축: O(N log N)
    keep = np.ones(len(positions), dtype=bool)

    for i in range(len(positions)):
        if not keep[i]:
            continue

        # threshold 이내의 모든 이웃 찾기: O(log N)
        neighbors = tree.query_ball_point(positions[i], distance_threshold)

        for j in neighbors:
            if j > i:  # 자기 뒤의 이웃만 제거 (순서 유지)
                keep[j] = False

    return positions[keep]
```

### 7.3 RDP 알고리즘 (Ramer-Douglas-Peucker)

직선 구간의 불필요한 중간점을 제거하는 알고리즘입니다.

```
[RDP 알고리즘]

Step 1: 시작점 A와 끝점 E를 잇는 직선
A ─────────────────── E
  B  C            D
   ∗  ∗            ∗

Step 2: 가장 먼 점 C 찾기 → 거리 = 0.3m

Step 3: 0.3m > epsilon(0.15m)? → Yes → 양쪽으로 재귀
  A ────── C          C ────── E
    B                   D

Step 4: A-C 구간에서 B 거리 = 0.05m < 0.15m → B 제거
        C-E 구간에서 D 거리 = 0.08m < 0.15m → D 제거

결과: A ─── C ─── E (5점 → 3점)
```

```python
def simplify_path_rdp(positions, epsilon=0.15):
    """재귀적으로 경로 단순화"""
    if len(positions) <= 2:
        return positions

    # 시작-끝 직선에서 가장 먼 점 찾기
    start, end = positions[0], positions[-1]
    max_dist = 0
    max_idx = 0

    for i in range(1, len(positions) - 1):
        dist = point_to_line_distance(positions[i], start, end)
        if dist > max_dist:
            max_dist = dist
            max_idx = i

    if max_dist > epsilon:
        # 재귀: 양쪽 분할
        left = simplify_path_rdp(positions[:max_idx+1], epsilon)
        right = simplify_path_rdp(positions[max_idx:], epsilon)
        return np.concatenate([left[:-1], right])
    else:
        # 중간점 모두 제거, 시작-끝만 유지
        return np.array([start, end])
```

---

## 8. Step 5: 스무딩 (smoothing.py)

### 가우시안 스무딩

1차원 가우시안 필터를 x, y, z 각 축에 독립 적용합니다.

```
[가우시안 필터란?]
각 점을 주변 점들의 가중 평균으로 대체.
가까운 점은 높은 가중치, 먼 점은 낮은 가중치 (종 모양 곡선).

    가중치
    │     ∗
    │   ∗   ∗
    │  ∗     ∗
    │ ∗       ∗
    │∗         ∗
    └──────────── 거리
      σ(시그마)가 크면 = 더 넓은 범위 평균 = 더 smooth
```

```python
from scipy.ndimage import gaussian_filter1d

DEFAULT_GAUSSIAN_SIGMA = 2.0  # 시그마 (필터 폭)

def smooth_path(positions, sigma=2.0):
    smoothed = positions.copy()

    # 각 축 독립 스무딩
    smoothed[:, 0] = gaussian_filter1d(positions[:, 0], sigma=sigma)  # X
    smoothed[:, 1] = gaussian_filter1d(positions[:, 1], sigma=sigma)  # Y
    smoothed[:, 2] = gaussian_filter1d(positions[:, 2], sigma=sigma)  # Z

    return smoothed
```

```
스무딩 효과:

sigma=0 (원본):  ∗∿∗∿∗∿∗∿∗∿∗  ← 노이즈 그대로
sigma=1:         ∗~∗~∗~∗~∗~∗  ← 약한 스무딩
sigma=2:         ∗-∗-∗-∗-∗-∗  ← 적당한 스무딩 (기본값)
sigma=5:         ∗──────────∗  ← 과도한 스무딩 (디테일 손실)
```

### 갭 인식 스무딩 (Gap-Aware Smoothing)

**왜 필요한가?**

왕복 제거 후 경로에 큰 간격(갭)이 생길 수 있습니다.
일반 스무딩은 갭을 넘어서 평균을 계산하여 **대각선 아티팩트**를 만듭니다.

```
문제 상황:
                      40m 갭
점들: ●●●●●●●●●●                    ●●●●●●●●●●
      A 구간                          B 구간

일반 스무딩 → 갭 근처에서 두 구간이 섞임:
●●●●●●●●╲                    ╱●●●●●●●●
          ╲__________________╱  ← 대각선 아티팩트!

갭 인식 스무딩 → 구간별 독립 스무딩:
●●●●●●●●●●                    ●●●●●●●●●●
A 구간 (독립 스무딩)            B 구간 (독립 스무딩)
```

```python
DEFAULT_GAP_THRESHOLD = 5.0  # 5m 이상이면 갭

def smooth_path_gapaware(positions, sigma=2.0, gap_threshold=5.0):
    # 1. 갭에서 세그먼트 분리
    segments = split_at_gaps(positions, gap_threshold)

    # 2. 각 세그먼트 독립 스무딩
    smoothed_segments = []
    for seg in segments:
        smoothed_segments.append(smooth_path(seg, sigma=sigma))

    # 3. 다시 합치기
    return np.concatenate(smoothed_segments, axis=0)

def split_at_gaps(positions, gap_threshold):
    """연속 포인트 간 거리가 gap_threshold 초과하면 분리"""
    dists = np.linalg.norm(np.diff(positions, axis=0), axis=1)
    split_indices = np.where(dists > gap_threshold)[0] + 1

    segments = np.split(positions, split_indices)
    return [s for s in segments if len(s) >= 2]
```

### 이상치 제거 (Outlier Removal)

```python
DEFAULT_OUTLIER_THRESHOLD = 3.0  # 3σ (표준편차)

def remove_outliers(positions):
    """평균 + 3σ 이상 벗어나는 점을 보간으로 교체"""
    dists = np.linalg.norm(np.diff(positions, axis=0), axis=1)
    mean_dist = np.mean(dists)
    std_dist = np.std(dists)
    threshold = mean_dist + 3.0 * std_dist

    result = positions.copy()
    for i in range(len(dists)):
        if dists[i] > threshold:
            # 선형 보간으로 교체
            result[i+1] = (positions[i] + positions[i+2]) / 2

    return result
```

---

## 9. Step 6: 그래프 구축 (junction_detection.py)

### 목표

경로 포인트 배열 → 길찾기용 **노드-엣지 그래프** 변환

```
포인트 배열:
●─●─●─●─●─●─●─●─●─●─●─●─●─●─●
               │
               ●─●─●─●─●

그래프:
◆───────────◇───────────◆
(ENDPOINT)  (JUNCTION)   (ENDPOINT)
             │
             ◆
           (ENDPOINT)

◆ = 노드 (ENDPOINT, JUNCTION, WAYPOINT 등)
─ = 엣지 (거리, 타입 포함)
```

### 노드 타입

```python
class NodeType:
    ENDPOINT      = "ENDPOINT"       # 경로의 시작/끝점
    JUNCTION      = "JUNCTION"       # 갈림길 (45° 이상 방향 변화)
    WAYPOINT      = "WAYPOINT"       # 일정 간격(1m)마다 생성
    POI_CANDIDATE = "POI_CANDIDATE"  # 막다른 길 (관심지점 후보)
```

### 갈림길 감지 알고리즘

```python
JUNCTION_ANGLE_THRESHOLD = 45.0  # 도
JUNCTION_MERGE_RADIUS = 1.5      # m (근접 갈림길 병합)

def detect_junctions(positions):
    junctions = []

    for i in range(1, len(positions) - 1):
        v1 = positions[i] - positions[i-1]      # 들어오는 방향
        v2 = positions[i+1] - positions[i]      # 나가는 방향

        angle = angle_between(v1, v2)

        if angle >= 45.0:
            junctions.append(i)

    # 가까운 갈림길 병합 (1.5m 이내)
    junctions = merge_nearby_junctions(junctions, positions, radius=1.5)

    return junctions
```

```
갈림길 감지 시각화:

    ●─●─●─●─●─●─●─●
                  ↗ 방향1 (v2)
    ●─●─●─●─●─●─◇
                  ↘ 방향2

    v1 (이전 방향) → 직진
    v2 (다음 방향) → 꺾임

    angle(v1, v2) = 90° > 45° → JUNCTION!
```

### 그래프 구축 전체 과정

```python
def build_path_graph(positions, floor_level):
    # 1. 갈림길 감지
    junctions = detect_junctions(positions)

    # 2. 노드 생성
    nodes = extract_path_nodes(positions, junctions, floor_level,
                                node_spacing=1.0)  # 1m 간격

    # 3. 엣지 생성 (연속된 노드 연결)
    edges = extract_path_edges(nodes, positions,
                                connection_radius=3.0)  # 3m 이내 연결

    return nodes, edges
```

### 노드 생성 규칙

```python
NODE_SPACING = 1.0  # m

def extract_path_nodes(positions, junctions, floor_level):
    nodes = []

    # 1. 끝점 추가
    nodes.append(PathNode(pos=positions[0],  type=ENDPOINT))
    nodes.append(PathNode(pos=positions[-1], type=ENDPOINT))

    # 2. 갈림길 추가
    for j in junctions:
        nodes.append(PathNode(pos=positions[j], type=JUNCTION))

    # 3. 일정 간격(1m)마다 WAYPOINT 추가
    cumulative_dist = 0
    for i in range(1, len(positions)):
        dist = np.linalg.norm(positions[i] - positions[i-1])
        cumulative_dist += dist

        if cumulative_dist >= NODE_SPACING:
            nodes.append(PathNode(pos=positions[i], type=WAYPOINT))
            cumulative_dist = 0

    return nodes
```

### 층간 그래프 병합

```python
def merge_floor_graphs(floor_graphs, vertical_passages):
    all_nodes, all_edges = [], []

    # 각 층의 그래프 합치기
    for floor_level, (nodes, edges) in floor_graphs.items():
        all_nodes.extend(nodes)
        all_edges.extend(edges)

    # 계단/엘리베이터로 층 연결
    for passage in vertical_passages:
        entry_node = find_nearest_node(all_nodes, passage['entry_point'])
        exit_node = find_nearest_node(all_nodes, passage['exit_point'])

        edge_type = "VERTICAL_STAIRCASE" if passage['type'] == "STAIRCASE" \
                   else "VERTICAL_ELEVATOR"

        all_edges.append(PathEdge(
            from_node=entry_node,
            to_node=exit_node,
            distance=passage['distance'],
            edge_type=edge_type
        ))

    return all_nodes, all_edges
```

```
최종 그래프 구조:

    [0층]                  [1층]                  [4층]
    ◆──○──○──◇           ◆──○──○──◇           ◆──○──○──◇
    │        │           │        │           │        │
    ○        ○           ○   ○    ○           ○        ○
    │        │           │   │    │           │        │
    ◆──○──○──◇           ◆──○──◇──◆           ◆──○──○──◆
         │                    │
         ╚════ 계단 ════╝    ╚════ 계단 ════╝

    ◆ = ENDPOINT, ◇ = JUNCTION, ○ = WAYPOINT
    ═ = VERTICAL_STAIRCASE 엣지
```

---

## 10. Step 7: 시각화 (visualization.py)

3종의 미리보기 이미지를 생성합니다.

### 이미지 종류

| 이미지 | 설명 | 서브플롯 |
|--------|------|----------|
| `_raw.png` | 원본 궤적 | 3D뷰, 위에서(XY), 옆에서(XZ), Z프로필 |
| `_processed.png` | 처리 후 | 3D뷰, 층별평면도, 수직통로, 통계 |
| `_comparison.png` | 전후 비교 | 좌: 원본 XY, 우: 처리 후 XY |

### 갭 분리 렌더링

시각화에서도 갭을 인식하여 대각선을 방지합니다.

```python
def _split_at_gaps(positions, max_gap=5.0):
    """5m 이상 갭에서 선을 끊어서 그림"""
    dists = np.linalg.norm(np.diff(positions, axis=0), axis=1)
    split_indices = np.where(dists > max_gap)[0] + 1

    segments = []
    prev = 0
    for idx in split_indices:
        if idx - prev >= 2:
            segments.append(positions[prev:idx])
        prev = idx
    if len(positions) - prev >= 2:
        segments.append(positions[prev:])

    return segments

# 사용: 각 세그먼트를 별도로 plot
for seg in _split_at_gaps(floor_positions):
    ax.plot(seg[:, 0], seg[:, 1], '-', color=color)
    # → 갭 사이에 선이 그려지지 않음!
```

---

## 11. FastAPI 서비스 (main.py)

### API 엔드포인트

| Method | URL | 설명 |
|--------|-----|------|
| `GET` | `/health` | 서비스 상태 확인 |
| `POST` | `/api/v1/upload` | .db 파일 업로드 |
| `POST` | `/api/v1/process/{file_id}` | 비동기 처리 시작 |
| `GET` | `/api/v1/jobs/{job_id}` | 작업 진행률 조회 |
| `GET` | `/api/v1/jobs/{job_id}/result` | 처리 결과 (JSON) |
| `GET` | `/api/v1/preview/{job_id}/{type}` | 미리보기 이미지 |

### 사용 흐름

```
1. 파일 업로드
   POST /api/v1/upload
   Body: form-data, file=school.db
   응답: { "file_id": "abc-123", "size_mb": 89.2 }

2. 처리 시작
   POST /api/v1/process/abc-123
   응답: { "job_id": "xyz-789", "status": "PENDING" }

3. 진행률 확인 (폴링)
   GET /api/v1/jobs/xyz-789
   응답: { "status": "PROCESSING", "progress": 65, "message": "스무딩 중..." }

4. 결과 조회
   GET /api/v1/jobs/xyz-789/result
   응답: {
     "floor_paths": [...],
     "vertical_passages": [...],
     "path_nodes": [...],        ← 길찾기 그래프 노드
     "path_edges": [...],        ← 길찾기 그래프 엣지
     "total_distance": 295.2
   }

5. 미리보기 이미지
   GET /api/v1/preview/xyz-789/processed
   응답: PNG 이미지
```

### 비동기 처리 구조

```python
# 백그라운드 태스크로 처리 (요청 즉시 응답)
@app.post("/api/v1/process/{file_id}")
async def start_processing(file_id: str, background_tasks: BackgroundTasks):
    job_id = str(uuid.uuid4())

    # 작업 상태 등록
    processing_jobs[job_id] = ProcessingJob(
        status="PENDING", progress=0
    )

    # 백그라운드에서 실행 (비동기)
    background_tasks.add_task(process_path_async, job_id, file_path)

    return {"job_id": job_id}  # 즉시 응답

# 실제 처리 (백그라운드)
async def process_path_async(job_id, file_path):
    job = processing_jobs[job_id]

    # CPU 바운드 작업은 to_thread로 실행
    positions, node_ids = await asyncio.to_thread(
        extract_trajectory_from_db, file_path
    )
    job.progress = 10
    job.message = "궤적 추출 완료"

    # ... 나머지 단계들 ...

    job.status = "COMPLETED"
    job.progress = 100
```

### 응답 데이터 모델

```python
# 그래프 노드
class PathNodeResponse:
    id: str              # "node_0F_0"
    x: float             # X 좌표 (미터)
    y: float             # Y 좌표 (미터)
    z: float             # Z 좌표 (미터)
    type: str            # ENDPOINT | JUNCTION | WAYPOINT | POI_CANDIDATE
    floor_level: int     # 0, 1, 4 등

# 그래프 엣지
class PathEdgeResponse:
    id: str              # "edge_0"
    from_node_id: str    # 시작 노드 ID
    to_node_id: str      # 끝 노드 ID
    distance: float      # 경로 거리 (미터)
    edge_type: str       # HORIZONTAL | VERTICAL_STAIRCASE | VERTICAL_ELEVATOR
    is_bidirectional: bool  # True (양방향)
```

---

## 12. 유틸리티 스크립트

### convert_mesh.py - 포인트클라우드 → 3D 메쉬

```python
import open3d as o3d

# 1. 합쳐진 포인트클라우드 로드
pcd = o3d.io.read_point_cloud("merged.ply")

# 2. 표면 법선 추정 (메쉬 생성에 필요)
pcd.estimate_normals()

# 3. 포아송 표면 재구성 (점 → 삼각형 면)
mesh, densities = o3d.geometry.TriangleMesh.create_from_point_cloud_poisson(
    pcd, depth=9  # depth 높을수록 정밀하지만 느림
)

# 4. 저장
o3d.io.write_triangle_mesh("mesh.ply", mesh)
```

### convert_to_2d.py - 3D 메쉬 → 2D 평면도

```python
# 다양한 높이에서 수평 단면을 추출하여 평면도 생성
heights = [0.5, 0.8, 1.0, 1.2]  # 미터

for h in heights:
    # 높이 h에서 수평면으로 자르기
    section = mesh.section(plane_origin=[0,0,h],
                           plane_normal=[0,0,1])
    # 2D 플롯으로 시각화
    plot_2d_section(section)
```

### point_merge.py - ICP 포인트클라우드 정합

```
[ICP (Iterative Closest Point) 알고리즘]
두 포인트클라우드를 정렬하는 알고리즘

반복:
  1. 클라우드2의 각 점에 대해 클라우드1에서 가장 가까운 점 찾기
  2. 대응점 쌍으로 최적 변환행렬(R, t) 계산
  3. 클라우드2에 변환 적용
  4. 오차가 충분히 작아질 때까지 반복

결과: 두 클라우드가 겹치도록 정렬됨 → 합치기
```

```python
import open3d as o3d

pcd1 = o3d.io.read_point_cloud("scan1.ply")
pcd2 = o3d.io.read_point_cloud("scan2.ply")

# ICP 실행
result = o3d.pipelines.registration.registration_icp(
    pcd2, pcd1,
    max_correspondence_distance=0.5,  # 대응점 최대 거리
    estimation_method=o3d.pipelines.registration.
        TransformationEstimationPointToPoint()
)

# 변환 적용 & 합치기
pcd2.transform(result.transformation)
merged = pcd1 + pcd2
o3d.io.write_point_cloud("merged.ply", merged)
```

---

## 13. 주요 파라미터 총정리

### 경로 직선화 파라미터

| 파라미터 | 기본값 | 단위 | 설명 |
|----------|--------|------|------|
| `angle_threshold` | 45.0 | 도 | 구간 분리 각도. 작으면 더 잘게 분할 |
| `linearity_threshold` | 0.85 | 비율(0~1) | PCA 직선성 임계값. 높으면 엄격 |
| `max_displacement` | 2.0 | m | 최대 허용 변위. 낮으면 보수적 |
| `min_segment_length` | 10 | 개 | 최소 구간 길이. 짧은 구간 무시 |

### 중복 제거 파라미터

| 파라미터 | 기본값 | 단위 | 설명 |
|----------|--------|------|------|
| `distance_threshold` | 0.3 (main), 0.5 (test) | m | KD-Tree 중복 반경 |
| `overlap_threshold` | 1.0 | m | 왕복 감지 반경 |
| `epsilon` (RDP) | 0.15 | m | 단순화 허용 오차. 작으면 더 많은 점 유지 |

### 스무딩 파라미터

| 파라미터 | 기본값 | 단위 | 설명 |
|----------|--------|------|------|
| `sigma` | 2.0 (test), 1.5 (main) | σ | 가우시안 필터 폭. 크면 더 smooth |
| `gap_threshold` | 5.0 | m | 갭 감지 거리. 이 이상이면 세그먼트 분리 |
| `outlier_threshold` | 3.0 | σ | 이상치 판별 (mean + 3σ) |

### 수직 통로 감지 파라미터

| 파라미터 | 기본값 | 단위 | 설명 |
|----------|--------|------|------|
| `z_change_threshold` | 0.05 | m/step | 포인트 간 최소 Z변화 |
| `window_size` | 10 | 개 | 슬라이딩 윈도우 크기 |
| `min_total_z_change` | 1.5 | m | 한 구간 최소 Z변화 (≈ 반 층) |
| `floor_height` | 3.0 | m | 표준 층 높이 |
| `elevator_xy_z_ratio` | 1.0 | 비율 | 계단/엘리베이터 구분 기준 |

### 그래프 구축 파라미터

| 파라미터 | 기본값 | 단위 | 설명 |
|----------|--------|------|------|
| `junction_angle` | 45.0 | 도 | 갈림길 판별 각도 |
| `node_spacing` | 1.0 | m | WAYPOINT 생성 간격 |
| `junction_merge_radius` | 1.5 | m | 근접 갈림길 병합 반경 |
| `edge_connection_radius` | 3.0 | m | 노드 연결 최대 거리 |

---

## 14. 알고리즘 복잡도 정리

| 알고리즘 | 위치 | 시간 복잡도 | 공간 복잡도 | 핵심 |
|----------|------|-------------|-------------|------|
| Pose 추출 | extraction.py | O(N) | O(N) | struct.unpack |
| PCA 직선 피팅 | path_flattening.py | O(N) | O(N) | 공분산 → 고유값 분해 |
| 직선 투영 | path_flattening.py | O(N) | O(N) | 내적 연산 |
| 구간 분할 | path_flattening.py | O(N) | O(K) | 각도 비교 |
| KD-Tree 중복 제거 | deduplication.py | O(N log N) | O(N) | cKDTree |
| RDP 단순화 | deduplication.py | O(N²) worst | O(N) | 재귀 분할 |
| 가우시안 스무딩 | smoothing.py | O(N) | O(N) | 1D 가우시안 필터 |
| 슬라이딩 윈도우 | vertical_detector.py | O(N) | O(1) | Z축 누적 변화 |
| Z 히스토그램 | vertical_detector.py | O(N) | O(B) | 빈도 계산 |
| 갈림길 감지 | junction_detection.py | O(N) | O(J) | 각도 비교 |
| 그래프 구축 | junction_detection.py | O(N) | O(N+E) | 노드+엣지 |

- N: 포인트 수, K: 세그먼트 수, B: 히스토그램 빈 수, J: 갈림길 수, E: 엣지 수

---

## 부록: 자주 사용하는 수학 개념

### 벡터 내적 (Dot Product)

```
a · b = |a| × |b| × cos(θ)

용도:
- 두 벡터 사이의 각도 계산
- 점을 직선에 투영 (project_points_to_line에서 사용)
```

### 공분산 행렬 (Covariance Matrix)

```
        ┌ Var(x)    Cov(x,y)  Cov(x,z) ┐
Cov =   │ Cov(y,x)  Var(y)    Cov(y,z) │
        └ Cov(z,x)  Cov(z,y)  Var(z)   ┘

- 대각선: 각 축의 분산 (데이터가 얼마나 퍼져있는지)
- 비대각선: 축 간의 상관관계
```

### 고유값 분해 (Eigendecomposition)

```
A × v = λ × v

A: 공분산 행렬 (3x3)
v: 고유벡터 (주성분 방향)
λ: 고유값 (해당 방향의 분산 크기)

가장 큰 고유값 = 데이터가 가장 많이 퍼진 방향 = 주 이동 방향
```

### 가우시안 함수

```
G(x) = (1/√(2πσ²)) × exp(-x²/(2σ²))

σ (시그마): 종 모양의 폭
- σ 작으면: 좁은 범위 평균 → 원본에 가까움
- σ 크면: 넓은 범위 평균 → 더 smooth

                  ∗
                ∗   ∗
              ∗       ∗
            ∗           ∗
    ───∗─────────────────────∗───
       -3σ  -2σ  -σ  0  σ  2σ  3σ
```
