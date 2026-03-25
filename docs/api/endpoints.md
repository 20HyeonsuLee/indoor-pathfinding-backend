# API 엔드포인트

> 현재 구현된 모든 REST API 목록입니다.
> Swagger UI: http://localhost:8080/swagger-ui.html

---

## Building (`/api/v1/buildings`)

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/v1/buildings` | 건물 생성 |
| GET | `/api/v1/buildings` | 건물 목록 (상태 필터 가능) |
| GET | `/api/v1/buildings/{id}` | 건물 상세 |
| PUT | `/api/v1/buildings/{id}` | 건물 수정 |
| DELETE | `/api/v1/buildings/{id}` | 건물 삭제 |
| DELETE | `/api/v1/buildings/batch` | 건물 일괄 삭제 |
| PATCH | `/api/v1/buildings/{id}/status` | 건물 상태 변경 |

---

## Floor (`/api/v1/buildings/{buildingId}/floors`, `/api/v1/floors`)

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/v1/buildings/{buildingId}/floors` | 층 생성 |
| GET | `/api/v1/buildings/{buildingId}/floors` | 건물의 층 목록 |
| GET | `/api/v1/floors/{floorId}` | 층 상세 |
| PUT | `/api/v1/floors/{floorId}` | 층 수정 |
| DELETE | `/api/v1/floors/{floorId}` | 층 삭제 |
| GET | `/api/v1/floors/{floorId}/path` | 층 경로 조회 |
| GET | `/api/v1/floors/{floorId}/pointcloud` | 층 PLY 포인트클라우드 다운로드 |

---

## Scan (`/api/v1/buildings/{buildingId}/scans`)

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/v1/buildings/{buildingId}/scans` | 스캔 파일(.db) 업로드 |
| GET | `/api/v1/buildings/{buildingId}/scans` | 스캔 세션 목록 |
| GET | `/api/v1/buildings/{buildingId}/scans/{sessionId}` | 스캔 세션 상세 |
| GET | `/api/v1/buildings/{buildingId}/scans/{sessionId}/pointcloud` | 스캔 PLY 다운로드 |

---

## Processing (`/api/v1/buildings/{buildingId}`)

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/v1/buildings/{buildingId}/process` | 처리 시작 |
| GET | `/api/v1/buildings/{buildingId}/process/status` | 처리 상태 조회 |
| POST | `/api/v1/buildings/{buildingId}/process/apply` | 처리 결과 적용 |
| GET | `/api/v1/buildings/{buildingId}/preview/{jobId}/{imageType}` | 프리뷰 이미지 |

---

## Pathfinding (`/api/v1/buildings/{buildingId}/pathfinding`)

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/v1/buildings/{buildingId}/pathfinding` | 경로 탐색 |

**요청 파라미터:**
- `startFloorLevel` (int) - 출발 층
- `startX`, `startY` (double) - 출발 좌표
- `startZ` (double, optional) - 출발 높이
- `destinationName` (string) - 목적지 POI 이름
- `preference` (enum, optional) - SHORTEST / ELEVATOR_FIRST / STAIRCASE_FIRST

---

## Graph Editor (`/api/v1/floors/{floorId}`, `/api/v1/nodes`, `/api/v1/edges`)

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/v1/floors/{floorId}/graph` | 층 그래프 조회 (노드 + 엣지) |
| POST | `/api/v1/floors/{floorId}/nodes` | 노드 생성 |
| PUT | `/api/v1/nodes/{nodeId}` | 노드 수정 |
| DELETE | `/api/v1/nodes/{nodeId}` | 노드 삭제 |
| POST | `/api/v1/floors/{floorId}/edges` | 엣지 생성 |
| DELETE | `/api/v1/edges/{edgeId}` | 엣지 삭제 |
| DELETE | `/api/v1/floors/{floorId}/graph` | 층 그래프 전체 삭제 |

---

## POI (`/api/v1/buildings/{buildingId}/pois`, `/api/v1/nodes/{nodeId}/poi`)

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/v1/buildings/{buildingId}/pois` | POI 생성 (노드 + POI 동시) |
| GET | `/api/v1/buildings/{buildingId}/pois` | 건물의 POI 목록 |
| GET | `/api/v1/buildings/{buildingId}/pois/search?query=` | POI 검색 |
| PUT | `/api/v1/nodes/{nodeId}/poi` | 기존 노드에 POI 등록 |
| DELETE | `/api/v1/nodes/{nodeId}/poi` | 노드에서 POI 제거 |

---

## Passage (`/api/v1/buildings/{buildingId}/passages`)

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/v1/buildings/{buildingId}/passages` | 수직통로 목록 (type 필터 가능) |
| GET | `/api/v1/passages/{passageId}` | 수직통로 상세 |

---

## Localization (`/api/v1/buildings/{buildingId}`)

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/v1/buildings/{buildingId}/localize` | VPS 위치 추정 |
| GET | `/api/v1/buildings/{buildingId}/slam/status` | SLAM 상태 조회 |
| GET | `/api/v1/buildings/{buildingId}/slam/metadata` | 맵 메타데이터 |
| POST | `/api/v1/buildings/{buildingId}/node-images` | 좌표 기반 주변 노드 이미지 조회 |

---

## Python Path Service (port 8000)

| Method | Path | 설명 |
|--------|------|------|
| GET | `/health` | 헬스체크 |
| POST | `/api/v1/upload` | .db 파일 업로드 → file_id |
| POST | `/api/v1/process/{file_id}` | 비동기 처리 시작 → job_id |
| GET | `/api/v1/jobs/{job_id}` | 작업 상태 조회 |
| GET | `/api/v1/jobs/{job_id}/result` | 처리 결과 조회 |
| POST | `/api/v1/pointcloud/extract` | 전체 PLY 추출 → cache_key |
| POST | `/api/v1/pointcloud/extract-floor` | 층별 PLY 추출 (Z-range) |
| GET | `/api/v1/pointcloud/{cache_key}/ply` | PLY 파일 다운로드 |

---

## 에러 코드

| 접두사 | 모듈 | 예시 |
|--------|------|------|
| B | Building | B001: 건물 미발견 |
| F | Floor | F001: 층 미발견 |
| S | Scan | S001: 스캔 세션 미발견, S003: 잘못된 파일 |
| PF | Pathfinding | PF001: 노드 미발견, PF005: 경로 없음, PF007: 중복 엣지 |
| V | VPS | V001: VPS 서비스 오류 |
| E | External | E001: 외부 서비스 오류 |
