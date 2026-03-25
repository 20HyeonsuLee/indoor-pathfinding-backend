# 모듈 설계

> 각 도메인 모듈의 **책임, 내부 구조, 모듈 간 관계**를 설명합니다.

---

## 모듈 전체 지도

```
modules/
├── building/        건물 관리 (루트 애그리거트)
├── floor/           층 관리 + 층별 경로
├── passage/         층간 이동 (계단/엘리베이터)
├── scan/            3D 스캔 파일 업로드
├── pathprocessing/  외부 경로 추출 서비스 연동
├── pathfinding/     A* 길찾기 + POI 관리
└── localization/    VPS 위치 추정
```

### 모듈 간 의존 관계

```
building <──── floor
    ^            ^
    |            |
    ├── scan     ├── pathfinding
    |            |
    └── passage ─┘
         ^
         |
    pathprocessing ──> (Python 서비스)

    localization ──> (VPS 서비스)

    scan ─ ─ ─ 이벤트 ─ ─ ─> localization
```

- 실선(──): 직접 의존 (FK 관계)
- 점선(─ ─): 이벤트 기반 느슨한 결합

---

## 1. Building 모듈

> 시스템의 최상위 애그리거트. 모든 데이터의 시작점입니다.

### 책임
- 건물 CRUD
- 건물 상태 관리 (DRAFT → ACTIVE)
- GPS 좌표(위경도)를 PostGIS Point로 관리

### 도메인 모델

```
Building
├── name (건물명)
├── description (설명)
├── location (PostGIS Point, SRID 4326)
├── status (DRAFT / ACTIVE)
├── floors[] (1:N)
├── verticalPassages[] (1:N)
└── scanSessions[] (1:N)
```

### 상태 전이

```
DRAFT ──(데이터 준비 완료)──> ACTIVE
```

### 서비스 구성

| 서비스 | 역할 |
|--------|------|
| `BuildingCreator` | 건물 생성 |
| `BuildingUpdater` | 정보 수정, 상태 변경 |
| `BuildingDeleter` | 삭제 (캐스케이드) |
| `BuildingReader` | 목록 조회, 상세 조회 (관계 즉시 로딩) |

> 소스 코드: [building/](../../src/main/java/com/koreatech/indoor_pathfinding/modules/building/)

---

## 2. Floor 모듈

> 건물의 각 층과 해당 층의 이동 경로를 관리합니다.

### 책임
- 층 CRUD
- 층별 경로 기하학(FloorPath) 관리
- 동일 건물 내 층 번호 중복 방지

### 도메인 모델

```
Floor
├── building (N:1)
├── level (층 번호, 정수)
├── name (층 이름)
├── height (층 높이, 선택)
└── floorPath (1:1)
      ├── pathGeometry (LineStringZ - 3D 선분)
      ├── segments[] (PathSegment 목록)
      ├── bounds (minX, maxX, minY, maxY)
      └── totalDistance
```

### 제약조건
- `(building_id, level)` 조합은 유일해야 합니다
- 층 삭제 시 하위 경로/노드도 함께 삭제됩니다

> 소스 코드: [floor/](../../src/main/java/com/koreatech/indoor_pathfinding/modules/floor/)

---

## 3. Passage 모듈

> 계단, 엘리베이터 같은 층간 이동 수단을 관리합니다.

### 책임
- 수직 이동로의 기하학 정보 관리
- 진입점/출구점 좌표 제공
- 유형별(계단/엘리베이터) 조회

### 도메인 모델

```
VerticalPassage
├── building (N:1)
├── fromFloor (출발 층)
├── toFloor (도착 층)
├── type (STAIRCASE / ELEVATOR)
├── pathGeometry (LineStringZ)
├── segments[] (PathSegment 목록)
├── entryPoint (x, y, z) - 진입 좌표
└── exitPoint (x, y, z) - 출구 좌표
```

### 길찾기와의 관계
- VerticalPassage의 진입/출구에는 `PASSAGE_ENTRY`, `PASSAGE_EXIT` 유형의 PathNode가 연결됩니다
- PathEdge의 유형이 `VERTICAL_STAIRCASE` 또는 `VERTICAL_ELEVATOR`로 설정되어 길찾기 시 가중치가 적용됩니다

> 소스 코드: [passage/](../../src/main/java/com/koreatech/indoor_pathfinding/modules/passage/)

---

## 4. Scan 모듈

> RTAB-Map으로 촬영한 .db 파일의 업로드와 처리 상태를 관리합니다.

### 책임
- 스캔 파일 업로드 (최대 500MB)
- 스캔 세션 상태 추적
- 처리 결과(노드 수, 총 거리) 기록
- 도메인 이벤트 발행

### 도메인 모델

```
ScanSession
├── building (N:1)
├── fileName / filePath / fileSize
├── status (상태 머신)
├── errorMessage (실패 시)
├── previewImagePath
├── processedPreviewPath
├── totalNodes (처리 후)
└── totalDistance (처리 후)
```

### 상태 전이

```
UPLOADED ──> EXTRACTING ──> PROCESSING ──> COMPLETED
                 |               |
                 └───> FAILED <──┘
```

### 이벤트

```
파일 업로드 완료
    |
    v
ScanFileUploadedEvent 발행
    |
    v
EventListener가 RTAB-Map 이미지 추출 시작
```

> 소스 코드: [scan/](../../src/main/java/com/koreatech/indoor_pathfinding/modules/scan/)

---

## 5. Path Processing 모듈

> Python FastAPI 서비스와의 연동을 담당합니다. 스캔 데이터에서 경로 그래프를 추출합니다.

### 책임
- Python 서비스에 파일 업로드
- 처리 작업 시작/상태 조회
- 결과를 DB에 적용 (노드, 엣지, 세그먼트 생성)

### 외부 서비스 통신

```
Spring Boot                         Python FastAPI
    |                                     |
    |── POST /api/v1/upload ──────────>   |  파일 전송
    |<── file_id ─────────────────────    |
    |                                     |
    |── POST /api/v1/process/{id} ────>   |  처리 시작
    |<── job_id ──────────────────────    |
    |                                     |
    |── GET /api/v1/jobs/{id} ────────>   |  상태 확인
    |<── {status, progress} ──────────    |
    |                                     |
    |── GET /api/v1/jobs/{id}/result ──>  |  결과 조회
    |<── {nodes, edges, segments} ────    |
```

### 도메인 모델

```
PathSegment
├── floorPath 또는 verticalPassage (소속)
├── sequenceOrder (순서)
├── startPoint (Point3D: x, y, z)
├── endPoint (Point3D: x, y, z)
└── length (자동 계산)
```

### 결과 적용 과정

```
Python 결과 JSON 수신
    |
    v
각 노드를 PathNode로 변환 (좌표, 유형 매핑)
    |
    v
노드 간 연결을 PathEdge로 변환 (거리 계산)
    |
    v
경로 조각을 PathSegment로 변환
    |
    v
FloorPath 업데이트 (기하학, 총 거리, 경계)
    |
    v
ScanSession 완료 처리 (totalNodes, totalDistance)
```

> 소스 코드: [pathprocessing/](../../src/main/java/com/koreatech/indoor_pathfinding/modules/pathprocessing/)

---

## 6. Pathfinding 모듈

> A* 알고리즘 기반 길찾기와 POI(관심 지점) 관리를 담당합니다.

### 책임
- 최적 경로 탐색 (출발 좌표 → 목적지 이름)
- 경로 선호도 반영 (최단/엘리베이터/계단)
- 턴바이턴 한국어 안내 생성
- POI CRUD 및 검색
- 그래프 수동 편집 (노드/엣지)

### 도메인 모델

```
PathNode (경로의 한 점)
├── floor (소속 층)
├── x, y, z (3D 좌표)
├── type
│   ├── WAYPOINT (일반 경유지)
│   ├── POI (관심 지점)
│   ├── PASSAGE_ENTRY (층간 이동 진입)
│   └── PASSAGE_EXIT (층간 이동 출구)
├── poiName ("컴공세미나실" 등)
├── poiCategory (CLASSROOM, OFFICE, RESTROOM, EXIT, ELEVATOR, STAIRCASE, OTHER)
└── verticalPassage (연결된 수직 이동로)

PathEdge (두 노드 간 연결)
├── fromNode / toNode
├── distance (물리적 거리)
├── edgeType
│   ├── HORIZONTAL (같은 층 이동)
│   ├── VERTICAL_STAIRCASE (계단)
│   └── VERTICAL_ELEVATOR (엘리베이터)
├── isBidirectional (양방향 여부)
└── getWeightedDistance(preference) → 선호도 반영 비용
```

### 길찾기 과정 (수도코드)

```
findPath(건물ID, 출발층, 출발좌표, 목적지이름, 선호도):
    1. 건물의 모든 층이 존재하는지 확인
    2. 출발좌표에서 가장 가까운 노드를 찾음
    3. 목적지이름으로 POI 노드를 찾음
    4. 건물 전체 노드/엣지를 메모리에 로드
    5. A*(출발노드, 목적지노드, 선호도) 실행
    6. 경로를 층 전환 기준으로 구간 분리
    7. 각 구간에 안내 문구 생성
    8. 총 거리와 예상 시간(보행속도 1.4m/s) 계산
```

### POI 관리

POI(관심 지점)에 대한 CRUD 및 검색 기능을 제공합니다.

| 작업 | 설명 |
|------|------|
| POI 생성 | 새 노드를 만들면서 POI 정보 등록 |
| POI 등록 | 기존 노드에 POI 정보를 추가 |
| POI 수정 | POI 이름, 카테고리 등 정보를 수정 |
| POI 제거 | 노드는 유지하되 POI 정보만 삭제 |
| POI 검색 | 이름으로 부분 일치 검색, 카테고리별 필터링 |

POI 카테고리: `CLASSROOM`, `OFFICE`, `RESTROOM`, `EXIT`, `ELEVATOR`, `STAIRCASE`, `OTHER`

### 그래프 편집기

관리자가 경로 그래프를 수동으로 편집할 수 있는 기능을 제공합니다. 3D 포인트클라우드 시각화 위에서 노드와 엣지를 직접 조작합니다.

| 작업 | 설명 |
|------|------|
| 노드 추가 | 3D 좌표를 지정하여 새 PathNode를 생성 |
| 노드 수정 | 기존 노드의 좌표, 유형, POI 정보를 변경 |
| 노드 삭제 | 노드와 연결된 엣지를 함께 삭제 |
| 엣지 추가 | 두 노드를 연결하는 PathEdge를 생성 (거리 자동 계산) |
| 엣지 삭제 | 노드 간 연결을 제거 |

> 소스 코드: [pathfinding/](../../src/main/java/com/koreatech/indoor_pathfinding/modules/pathfinding/)

---

## 7. Localization 모듈

> VPS(Visual Positioning System)와 연동하여 카메라 이미지로 실내 위치를 추정합니다.

### 책임
- 카메라 이미지를 VPS 서비스에 전송
- SLAM 처리 상태 조회
- 맵 메타데이터 관리
- RTAB-Map에서 참조 이미지 추출
- 좌표 기반 주변 노드 이미지 조회

### 외부 서비스 통신

```
클라이언트                Spring Boot                VPS 서비스
    |                       |                          |
    |── 이미지 전송 ────>   |                          |
    |                       |── 맵 메타데이터 조회 ──>  |
    |                       |<── mapId ──────────────  |
    |                       |                          |
    |                       |── 이미지 + mapId ──────> |
    |                       |<── 위치 추정 결과 ─────  |
    |                       |                          |
    |<── 위치 좌표 반환 ──  |                          |
```

### RTAB-Map 이미지 추출

RTAB-Map의 .db 파일은 실제로 **SQLite 데이터베이스**입니다. 이 안에 3D 스캔 시 촬영된 이미지가 포함되어 있습니다. `RtabMapImageExtractor`가 SQLite JDBC로 직접 접근하여 이미지를 추출합니다.

> 소스 코드: [localization/](../../src/main/java/com/koreatech/indoor_pathfinding/modules/localization/)

---

## 공통 모듈 (shared/)

> 모든 도메인 모듈이 공유하는 기반 코드입니다.

```
shared/
├── config/
│   ├── CorsConfig         CORS 설정
│   ├── WebMvcConfig       MVC 설정 (정적 파일 서빙 등)
│   └── SwaggerConfig      API 문서 설정
├── domain/
│   └── BaseEntity         모든 엔티티의 부모 (UUID id, createdAt, updatedAt)
└── exception/
    ├── BusinessException       비즈니스 예외
    ├── EntityNotFoundException 엔티티 미존재 예외
    ├── ErrorCode               모듈별 에러 코드 열거형
    ├── ErrorResponse           통일된 에러 응답 형식
    └── GlobalExceptionHandler  전역 예외 처리 (@RestControllerAdvice)
```

### BaseEntity

모든 도메인 엔티티가 상속받으며, 다음 필드를 자동 관리합니다:
- `id` - UUID (자동 생성)
- `createdAt` - 생성 시각 (자동)
- `updatedAt` - 수정 시각 (자동)

> 소스 코드: [shared/](../../src/main/java/com/koreatech/indoor_pathfinding/shared/)

---

## 각 모듈의 패키지 구조 (공통)

모든 모듈은 동일한 내부 구조를 따릅니다:

```
modules/{도메인명}/
├── domain/
│   ├── model/          엔티티, Value Object, Enum
│   └── repository/     Spring Data JPA Repository 인터페이스
├── application/
│   ├── service/        유스케이스 서비스 (트랜잭션 관리)
│   └── dto/            요청/응답 DTO (Record)
├── infrastructure/
│   └── external/       외부 서비스 클라이언트 (해당 시에만)
└── interfaces/
    └── controller/     REST 컨트롤러
```
