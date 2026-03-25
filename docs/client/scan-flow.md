# 스캔/처리 플로우 가이드 (프론트엔드 참조용)

> 이 문서는 관리자 UI에서 건물의 실내 스캔 데이터를 업로드하고 처리하는 전체 플로우를 설명합니다.
> 소스 코드 기준으로 작성되었으며, 실제 필드명과 타입을 그대로 사용합니다.

---

## 1. 전체 플로우 요약

```
[1] 건물 생성
     │
     ▼
[2] 층 생성
     │
     ▼
[3] 청크(.db) 업로드  ──── 필요 시 반복 (1개 이상)
     │
     ▼
[4] 청크 병합 요청  ──── 단일 청크: 즉시 MERGED / 다중 청크: MERGING → MERGED
     │
     ▼
[5] 경로 처리 시작  ──── 비동기 처리 (EXTRACTING → PROCESSING → COMPLETED)
     │
     ▼
[6] 처리 상태 폴링  ──── 처리 완료까지 주기적 조회
     │
     ▼
[7] PLY 포인트클라우드 확인/다운로드
```

---

## 2. 각 단계별 API 상세

### 2-1. 건물 생성

| 항목 | 값 |
|------|-----|
| Method | `POST` |
| URL | `/api/v1/buildings` |
| Content-Type | `application/json` |
| 응답 코드 | `201 Created` |

**Request Body:**

```json
{
  "name": "한기대 2공학관",
  "description": "천안시 서북구 한기대길 소재 건물",
  "latitude": 36.7635,
  "longitude": 127.2814
}
```

| 필드 | 타입 | 필수 | 검증 규칙 |
|------|------|------|-----------|
| `name` | `String` | O | 공백 불가, 최대 100자 |
| `description` | `String` | X | 최대 1000자 |
| `latitude` | `Double` | X | |
| `longitude` | `Double` | X | |

**Response Body:**

```json
{
  "id": "a1b2c3d4-5678-9012-abcd-ef1234567890",
  "name": "한기대 2공학관",
  "description": "천안시 서북구 한기대길 소재 건물",
  "latitude": 36.7635,
  "longitude": 127.2814,
  "status": "DRAFT",
  "floorCount": 0,
  "passageCount": 0,
  "createdAt": "2026-03-25T10:00:00",
  "updatedAt": "2026-03-25T10:00:00"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `id` | `UUID` | 건물 고유 ID |
| `status` | `BuildingStatus` | `DRAFT` 또는 `ACTIVE` |
| `floorCount` | `int` | 소속 층 수 |
| `passageCount` | `int` | 수직통로 수 |

**주의사항:**
- 생성 직후 `status`는 항상 `DRAFT`입니다.
- 모든 층 설정이 완료된 후 `PATCH /api/v1/buildings/{id}/status`로 `ACTIVE`로 전환하세요.

---

### 2-2. 층 생성

| 항목 | 값 |
|------|-----|
| Method | `POST` |
| URL | `/api/v1/buildings/{buildingId}/floors` |
| Content-Type | `application/json` |
| 응답 코드 | `201 Created` |

**Request Body:**

```json
{
  "name": "1층",
  "level": 1,
  "height": 3.5
}
```

| 필드 | 타입 | 필수 | 검증 규칙 |
|------|------|------|-----------|
| `name` | `String` | O | 공백 불가, 최대 50자 |
| `level` | `Integer` | O | 층 번호 (정수) |
| `height` | `Double` | X | 층 높이(미터) |

**Response Body:**

```json
{
  "id": "b2c3d4e5-6789-0123-bcde-f12345678901",
  "name": "1층",
  "level": 1,
  "height": 3.5,
  "hasPath": false,
  "hasPly": false
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `id` | `UUID` | 층 고유 ID (이후 모든 스캔/처리 API에서 사용) |
| `hasPath` | `boolean` | 경로 데이터 존재 여부 |
| `hasPly` | `boolean` | PLY 포인트클라우드 존재 여부 |

**주의사항:**
- 동일 건물에 동일 `level`이 이미 존재하면 `409 Conflict` (`F002`) 에러가 발생합니다.
- 반환된 `id`를 이후 청크 업로드, 병합, 처리 API의 `floorId` 파라미터로 사용합니다.

---

### 2-3. 청크 업로드

| 항목 | 값 |
|------|-----|
| Method | `POST` |
| URL | `/api/v1/floors/{floorId}/scans/chunks` |
| Content-Type | `multipart/form-data` |
| 응답 코드 | `201 Created` |

**Request:**

```
POST /api/v1/floors/b2c3d4e5-6789-0123-bcde-f12345678901/scans/chunks
Content-Type: multipart/form-data

file: (RTAB-Map .db 파일 바이너리)
```

| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| `file` | `MultipartFile` | O | RTAB-Map에서 출력한 `.db` 파일 |

**Response Body:**

```json
{
  "id": "c3d4e5f6-7890-1234-cdef-123456789012",
  "floorId": "b2c3d4e5-6789-0123-bcde-f12345678901",
  "fileName": "scan_part1.db",
  "fileSize": 52428800,
  "status": "UPLOADED",
  "active": true,
  "uploadOrder": 1,
  "errorMessage": null,
  "createdAt": "2026-03-25T10:05:00"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `id` | `UUID` | 청크 고유 ID |
| `floorId` | `UUID` | 소속 층 ID |
| `fileName` | `String` | 업로드한 원본 파일명 |
| `fileSize` | `Long` | 파일 크기 (바이트) |
| `status` | `ChunkStatus` | `UPLOADED` 또는 `FAILED` |
| `active` | `boolean` | 병합 대상 포함 여부 |
| `uploadOrder` | `int` | 업로드 순서 (1부터 시작) |
| `errorMessage` | `String?` | 업로드 실패 시 에러 메시지 |
| `createdAt` | `LocalDateTime` | 업로드 시각 |

**주의사항:**
- 파일 확장자가 `.db`가 아니면 `400 Bad Request` (`SC002`) 에러가 발생합니다.
- 같은 층에 여러 청크를 업로드하면 `uploadOrder`가 자동으로 증가합니다.
- 업로드된 청크는 기본적으로 `active: true` 상태입니다.

---

### 2-4. 청크 목록 조회

| 항목 | 값 |
|------|-----|
| Method | `GET` |
| URL | `/api/v1/floors/{floorId}/scans/chunks` |
| 응답 코드 | `200 OK` |

**Response Body:**

```json
[
  {
    "id": "c3d4e5f6-7890-1234-cdef-123456789012",
    "floorId": "b2c3d4e5-6789-0123-bcde-f12345678901",
    "fileName": "scan_part1.db",
    "fileSize": 52428800,
    "status": "UPLOADED",
    "active": true,
    "uploadOrder": 1,
    "errorMessage": null,
    "createdAt": "2026-03-25T10:05:00"
  },
  {
    "id": "d4e5f6a7-8901-2345-def0-234567890123",
    "floorId": "b2c3d4e5-6789-0123-bcde-f12345678901",
    "fileName": "scan_part2.db",
    "fileSize": 48234567,
    "status": "UPLOADED",
    "active": true,
    "uploadOrder": 2,
    "errorMessage": null,
    "createdAt": "2026-03-25T10:06:00"
  }
]
```

---

### 2-5. 청크 삭제

| 항목 | 값 |
|------|-----|
| Method | `DELETE` |
| URL | `/api/v1/floors/{floorId}/scans/chunks/{chunkId}` |
| 응답 코드 | `204 No Content` |

**주의사항:**
- 삭제 후에는 복구할 수 없습니다. 서버 디스크의 파일도 함께 삭제됩니다.
- 이미 병합에 사용된 청크를 삭제해도 기존 병합 결과에는 영향을 주지 않습니다. 단, 재병합 시 해당 청크는 사용할 수 없습니다.

---

### 2-6. 청크 병합

| 항목 | 값 |
|------|-----|
| Method | `POST` |
| URL | `/api/v1/floors/{floorId}/scans/merge` |
| Content-Type | `application/json` |
| 응답 코드 | `200 OK` |

**Request Body:**

```json
{
  "chunkIds": [
    "c3d4e5f6-7890-1234-cdef-123456789012",
    "d4e5f6a7-8901-2345-def0-234567890123"
  ]
}
```

| 필드 | 타입 | 필수 | 검증 규칙 |
|------|------|------|-----------|
| `chunkIds` | `List<UUID>` | O | 1개 이상 필수 (`@NotEmpty`) |

**Response Body (단일 청크 - 즉시 MERGED):**

```json
{
  "id": "e5f6a7b8-9012-3456-ef01-345678901234",
  "floorId": "b2c3d4e5-6789-0123-bcde-f12345678901",
  "status": "MERGED",
  "plyFileId": null,
  "totalNodes": null,
  "totalDistance": null,
  "errorMessage": null,
  "createdAt": "2026-03-25T10:10:00",
  "updatedAt": "2026-03-25T10:10:00"
}
```

**Response Body (다중 청크 - 병합 시작):**

```json
{
  "id": "e5f6a7b8-9012-3456-ef01-345678901234",
  "floorId": "b2c3d4e5-6789-0123-bcde-f12345678901",
  "status": "MERGING",
  "plyFileId": null,
  "totalNodes": null,
  "totalDistance": null,
  "errorMessage": null,
  "createdAt": "2026-03-25T10:10:00",
  "updatedAt": "2026-03-25T10:10:00"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `id` | `UUID` | 병합 스캔 고유 ID |
| `floorId` | `UUID` | 소속 층 ID |
| `status` | `MergedScanStatus` | 현재 병합/처리 상태 |
| `plyFileId` | `String?` | PLY 파일 캐시 키 (처리 완료 후 설정됨) |
| `totalNodes` | `Integer?` | 추출된 총 노드 수 (처리 완료 후 설정됨) |
| `totalDistance` | `Double?` | 총 경로 거리 (처리 완료 후 설정됨) |
| `errorMessage` | `String?` | 실패 시 에러 메시지 |

**주의사항:**
- 같은 층에 기존 병합 결과가 있으면 **자동으로 삭제**되고 새로 생성됩니다.
- `chunkIds`에 포함된 모든 청크는 해당 `floorId`에 소속되어야 합니다. 다른 층의 청크 ID를 넣으면 `400 Bad Request` (`C001`) 에러가 발생합니다.
- 존재하지 않는 `chunkId`를 포함하면 `404 Not Found` (`SC001`) 에러가 발생합니다.
- 빈 배열을 보내면 `400 Bad Request` (validation 실패) 에러가 발생합니다.

---

### 2-7. 병합 상태 조회

| 항목 | 값 |
|------|-----|
| Method | `GET` |
| URL | `/api/v1/floors/{floorId}/scans/merge/status` |
| 응답 코드 | `200 OK` |

**Response Body:**

```json
{
  "id": "e5f6a7b8-9012-3456-ef01-345678901234",
  "floorId": "b2c3d4e5-6789-0123-bcde-f12345678901",
  "status": "MERGED",
  "plyFileId": null,
  "totalNodes": null,
  "totalDistance": null,
  "errorMessage": null,
  "createdAt": "2026-03-25T10:10:00",
  "updatedAt": "2026-03-25T10:10:05"
}
```

**주의사항:**
- 병합 요청을 한 번도 하지 않은 층에 대해 호출하면 `404 Not Found` (`MS001`) 에러가 발생합니다.

---

### 2-8. 경로 처리 시작

| 항목 | 값 |
|------|-----|
| Method | `POST` |
| URL | `/api/v1/floors/{floorId}/process` |
| 응답 코드 | `200 OK` |

**Request:** 별도 Body 없음 (Path Variable만 사용)

**Response Body:**

```json
{
  "jobId": "job_abc123def456",
  "floorId": "b2c3d4e5-6789-0123-bcde-f12345678901"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `jobId` | `String` | Python 서비스에서 부여한 비동기 작업 ID |
| `floorId` | `UUID` | 처리 대상 층 ID |

**주의사항:**
- 병합 상태가 `MERGED`, `COMPLETED`, `FAILED` 중 하나여야 처리를 시작할 수 있습니다.
- `MERGING`, `EXTRACTING`, `PROCESSING` 상태에서는 `400 Bad Request` (`MS003`) 에러가 발생합니다.
- 이전에 처리가 완료(`COMPLETED`)되었거나 실패(`FAILED`)한 경우에도 다시 처리를 시작할 수 있습니다 (재처리).
- 처리는 **비동기**로 실행됩니다. 응답을 받은 후 상태 조회 API로 폴링하세요.

---

### 2-9. 처리 상태 조회 (폴링)

| 항목 | 값 |
|------|-----|
| Method | `GET` |
| URL | `/api/v1/floors/{floorId}/process/status` |
| 응답 코드 | `200 OK` |

**Response Body (진행 중):**

```json
{
  "jobId": "job_abc123def456",
  "status": "PROCESSING",
  "progress": 45,
  "message": "Extracting graph nodes...",
  "createdAt": "2026-03-25T10:12:00",
  "completedAt": null,
  "error": null
}
```

**Response Body (완료):**

```json
{
  "jobId": "job_abc123def456",
  "status": "COMPLETED",
  "progress": 100,
  "message": "Processing completed successfully",
  "createdAt": "2026-03-25T10:12:00",
  "completedAt": "2026-03-25T10:15:30",
  "error": null
}
```

**Response Body (실패):**

```json
{
  "jobId": "job_abc123def456",
  "status": "FAILED",
  "progress": 30,
  "message": "Processing failed",
  "createdAt": "2026-03-25T10:12:00",
  "completedAt": null,
  "error": "Failed to extract nodes from database"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `jobId` | `String` | 작업 ID |
| `status` | `String` | `PROCESSING`, `COMPLETED`, `FAILED` 등 |
| `progress` | `int` | 진행률 (0-100) |
| `message` | `String` | 현재 단계 설명 |
| `createdAt` | `String` | 작업 생성 시각 |
| `completedAt` | `String?` | 작업 완료 시각 |
| `error` | `String?` | 실패 시 에러 상세 |

**주의사항:**
- 권장 폴링 주기: **5~10초**
- `status`가 `COMPLETED` 또는 `FAILED`가 되면 폴링을 중단하세요.
- 처리 완료 후 서버가 자동으로 경로 데이터를 적용하고 PLY를 추출합니다.

---

### 2-10. 미리보기 이미지 조회

| 항목 | 값 |
|------|-----|
| Method | `GET` |
| URL | `/api/v1/floors/{floorId}/preview/{jobId}/{imageType}` |
| 응답 코드 | `200 OK` |
| Content-Type | `image/png` |

**Request 예시:**

```
GET /api/v1/floors/b2c3d4e5-6789-0123-bcde-f12345678901/preview/job_abc123def456/graph
```

| 파라미터 | 타입 | 설명 |
|----------|------|------|
| `floorId` | `UUID` | 층 ID |
| `jobId` | `String` | 처리 작업 ID |
| `imageType` | `String` | 이미지 종류 (예: `graph`, `occupancy_grid` 등) |

**Response:** PNG 이미지 바이너리

---

### 2-11. PLY 포인트클라우드 다운로드

| 항목 | 값 |
|------|-----|
| Method | `GET` |
| URL | `/api/v1/floors/{floorId}/pointcloud` |
| 응답 코드 | `200 OK` |
| Content-Type | `application/octet-stream` |

**Response:** PLY 파일 바이너리 (Content-Disposition 헤더로 파일명 제공)

**주의사항:**
- 처리가 완료되어 `plyFileId`가 설정된 층에서만 다운로드 가능합니다.
- PLY가 아직 생성되지 않은 층에서 호출하면 `503 Service Unavailable` (`E001`) 에러가 발생합니다.

---

## 3. 시나리오별 가이드

### 시나리오 A: 작은 층 (청크 1개로 충분)

하나의 스캔 세션으로 전체 층을 커버할 수 있는 경우입니다.

```
[1] POST /api/v1/buildings
    → buildingId: "a1b2c3d4-..."

[2] POST /api/v1/buildings/a1b2c3d4-.../floors
    → floorId: "b2c3d4e5-..."

[3] POST /api/v1/floors/b2c3d4e5-.../scans/chunks
    (file: single_scan.db)
    → chunkId: "c3d4e5f6-..."

[4] POST /api/v1/floors/b2c3d4e5-.../scans/merge
    Body: { "chunkIds": ["c3d4e5f6-..."] }
    → status: "MERGED"  (단일 청크이므로 즉시 완료)

[5] POST /api/v1/floors/b2c3d4e5-.../process
    → jobId: "job_abc123..."

[6] GET /api/v1/floors/b2c3d4e5-.../process/status
    → 반복 폴링 (5~10초 간격)
    → status: "COMPLETED" 확인

[7] GET /api/v1/floors/b2c3d4e5-.../pointcloud
    → PLY 파일 다운로드
```

---

### 시나리오 B: 큰 층 (청크 3개 분할 업로드 후 병합)

넓은 층을 여러 구역으로 나누어 스캔한 경우입니다.

```
[1~2] 건물/층 생성 (시나리오 A와 동일)

[3-a] POST /api/v1/floors/{floorId}/scans/chunks
      (file: zone_A.db)
      → chunkId_A: "c3d4e5f6-..."  (uploadOrder: 1)

[3-b] POST /api/v1/floors/{floorId}/scans/chunks
      (file: zone_B.db)
      → chunkId_B: "d4e5f6a7-..."  (uploadOrder: 2)

[3-c] POST /api/v1/floors/{floorId}/scans/chunks
      (file: zone_C.db)
      → chunkId_C: "e5f6a7b8-..."  (uploadOrder: 3)

[확인] GET /api/v1/floors/{floorId}/scans/chunks
      → 3개 청크 목록 확인

[4] POST /api/v1/floors/{floorId}/scans/merge
    Body: {
      "chunkIds": [
        "c3d4e5f6-...",
        "d4e5f6a7-...",
        "e5f6a7b8-..."
      ]
    }
    → status: "MERGING"  (다중 청크이므로 비동기 병합 시작)

[4-폴링] GET /api/v1/floors/{floorId}/scans/merge/status
         → status: "MERGED" 될 때까지 폴링

[5~7] 처리 시작 → 폴링 → PLY 다운로드 (시나리오 A와 동일)
```

---

### 시나리오 C: 부분 업데이트 (기존 청크 중 1개 교체 후 재병합)

이미 병합/처리된 층에서 특정 구역의 스캔을 다시 촬영한 경우입니다.

```
[현재 상태] 층에 3개 청크가 있고, 병합/처리가 완료된 상태

[1] GET /api/v1/floors/{floorId}/scans/chunks
    → 기존 청크 목록 확인:
      chunkId_A (uploadOrder: 1)  -- 유지
      chunkId_B (uploadOrder: 2)  -- 교체 대상
      chunkId_C (uploadOrder: 3)  -- 유지

[2] DELETE /api/v1/floors/{floorId}/scans/chunks/chunkId_B
    → 기존 zone_B 청크 삭제

[3] POST /api/v1/floors/{floorId}/scans/chunks
    (file: zone_B_rescan.db)
    → chunkId_B_new: "f6a7b8c9-..."

[4] POST /api/v1/floors/{floorId}/scans/merge
    Body: {
      "chunkIds": [
        "chunkId_A",
        "f6a7b8c9-...",
        "chunkId_C"
      ]
    }
    → 기존 병합 결과가 자동 삭제되고, 새로 병합 시작
    → status: "MERGING"

[5] 병합 완료 확인 후 재처리
    POST /api/v1/floors/{floorId}/process
    → 새로운 jobId 발급

[6~7] 폴링 → PLY 다운로드 (동일)
```

**핵심:** 병합 API 호출 시 기존 `MergedScan`이 자동으로 교체되므로 별도의 삭제 과정이 필요 없습니다.

---

## 4. 상태 흐름도

### 4-1. MergedScanStatus 상태 전이

```
                    ┌──────────────┐
                    │   (초기 상태)  │
                    └──────┬───────┘
                           │
               ┌───────────┼───────────┐
               │ 단일 청크  │           │ 다중 청크
               │           │           │
               ▼           │           ▼
          ┌────────┐       │     ┌──────────┐
          │ MERGED │       │     │ MERGING  │
          └───┬────┘       │     └────┬─────┘
              │            │          │
              │            │     ┌────┼──────────┐
              │            │     │ 성공          │ 실패
              │            │     ▼               ▼
              │            │  ┌────────┐  ┌──────────────┐
              │            │  │ MERGED │  │ MERGE_FAILED │
              │            │  └───┬────┘  └──────────────┘
              │            │      │
              └────────────┘      │
                       │          │
                       ▼          │
               ┌─────────────┐   │
               │ EXTRACTING  │◄──┘  (POST /process 호출)
               └──────┬──────┘
                      │
                      ▼
               ┌─────────────┐
               │ PROCESSING  │
               └──────┬──────┘
                      │
              ┌───────┼───────┐
              │ 성공          │ 실패
              ▼               ▼
        ┌───────────┐   ┌──────────┐
        │ COMPLETED │   │  FAILED  │
        └─────┬─────┘   └────┬─────┘
              │               │
              └───────┬───────┘
                      │
                      ▼  (재처리 가능)
              ┌─────────────┐
              │ EXTRACTING  │
              └─────────────┘
```

### 4-2. 각 상태 설명

| 상태 | 설명 | 다음 가능한 상태 |
|------|------|-----------------|
| `MERGING` | 다중 청크 병합 중 (비동기) | `MERGED`, `MERGE_FAILED` |
| `MERGED` | 병합 완료, 처리 대기 중 | `EXTRACTING` |
| `MERGE_FAILED` | 병합 실패 | (재병합 필요) |
| `EXTRACTING` | Python 서비스에 파일 업로드 후 처리 시작됨 | `PROCESSING` |
| `PROCESSING` | 경로 추출 처리 중 | `COMPLETED`, `FAILED` |
| `COMPLETED` | 처리 완료, 경로 데이터 및 PLY 사용 가능 | `EXTRACTING` (재처리 시) |
| `FAILED` | 처리 실패 | `EXTRACTING` (재처리 시) |

### 4-3. 처리 가능 조건 (`isProcessable`)

`POST /process` API를 호출할 수 있는 상태:

| 상태 | 처리 시작 가능 여부 |
|------|---------------------|
| `MERGING` | X |
| `MERGED` | **O** |
| `MERGE_FAILED` | X |
| `EXTRACTING` | X |
| `PROCESSING` | X |
| `COMPLETED` | **O** (재처리) |
| `FAILED` | **O** (재처리) |

---

## 5. 에러 처리

### 5-1. 공통 에러 응답 형식

모든 에러는 다음 형식으로 반환됩니다:

```json
{
  "timestamp": "2026-03-25T10:30:00",
  "code": "SC002",
  "message": "Invalid scan file",
  "status": 400
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `timestamp` | `LocalDateTime` | 에러 발생 시각 |
| `code` | `String` | 에러 코드 (아래 표 참조) |
| `message` | `String` | 에러 메시지 |
| `status` | `int` | HTTP 상태 코드 |

### 5-2. 단계별 에러 코드

#### 건물 관련

| 코드 | HTTP | 메시지 | 원인 | 대응 |
|------|------|--------|------|------|
| `B001` | 404 | Building not found | 존재하지 않는 건물 ID | 건물 ID 확인 |
| `B003` | 400 | Invalid building status | 유효하지 않은 상태 값 | `DRAFT` 또는 `ACTIVE`만 가능 |

#### 층 관련

| 코드 | HTTP | 메시지 | 원인 | 대응 |
|------|------|--------|------|------|
| `F001` | 404 | Floor not found | 존재하지 않는 층 ID | 층 ID 확인 |
| `F002` | 409 | Floor already exists | 동일 건물에 같은 level 존재 | 다른 level 값 사용 |

#### 청크 업로드 관련

| 코드 | HTTP | 메시지 | 원인 | 대응 |
|------|------|--------|------|------|
| `SC001` | 404 | Scan chunk not found | 존재하지 않는 청크 ID | 청크 ID 확인 |
| `SC002` | 400 | Invalid scan file | .db 파일이 아니거나 손상됨 | 파일 형식 확인 |
| `SC003` | 400 | No active chunks to merge | 활성 청크가 없음 | 청크 업로드 후 재시도 |

#### 병합/처리 관련

| 코드 | HTTP | 메시지 | 원인 | 대응 |
|------|------|--------|------|------|
| `MS001` | 404 | Merged scan not found | 병합 이력이 없는 층 | 먼저 병합 수행 |
| `MS002` | 500 | Merge processing failed | 서버 내부 병합 오류 | 관리자 문의 또는 재시도 |
| `MS003` | 400 | Merged scan is not in a processable state | `MERGING`/`EXTRACTING`/`PROCESSING` 상태에서 처리 시도 | 현재 작업 완료까지 대기 |
| `MS004` | 500 | Scan processing failed | Python 서비스 처리 실패 | 로그 확인 후 재처리 |

#### 공통/외부 서비스

| 코드 | HTTP | 메시지 | 원인 | 대응 |
|------|------|--------|------|------|
| `C001` | 400 | Invalid input value | 요청 데이터 검증 실패 | 요청 파라미터 확인 |
| `C002` | 500 | Internal server error | 예상하지 못한 서버 오류 | 관리자 문의 |
| `E001` | 503 | External service error | Python 서비스 연결 실패 | 서비스 상태 확인 후 재시도 |

### 5-3. Validation 에러 (MethodArgumentNotValidException)

필수 필드 누락 등의 검증 실패 시 `400 Bad Request`와 함께 상세한 필드 에러 정보가 반환됩니다.

**예시 - 빈 chunkIds로 병합 요청:**

```
POST /api/v1/floors/{floorId}/scans/merge
Body: { "chunkIds": [] }
```

```json
{
  "timestamp": "2026-03-25T10:30:00",
  "code": "C001",
  "message": "At least one chunk ID is required",
  "status": 400
}
```

---

## 부록: API 엔드포인트 빠른 참조

| 단계 | Method | URL | 설명 |
|------|--------|-----|------|
| 1 | `POST` | `/api/v1/buildings` | 건물 생성 |
| 2 | `POST` | `/api/v1/buildings/{buildingId}/floors` | 층 생성 |
| 3 | `POST` | `/api/v1/floors/{floorId}/scans/chunks` | 청크 업로드 |
| - | `GET` | `/api/v1/floors/{floorId}/scans/chunks` | 청크 목록 조회 |
| - | `DELETE` | `/api/v1/floors/{floorId}/scans/chunks/{chunkId}` | 청크 삭제 |
| 4 | `POST` | `/api/v1/floors/{floorId}/scans/merge` | 청크 병합 |
| - | `GET` | `/api/v1/floors/{floorId}/scans/merge/status` | 병합 상태 조회 |
| 5 | `POST` | `/api/v1/floors/{floorId}/process` | 처리 시작 |
| 6 | `GET` | `/api/v1/floors/{floorId}/process/status` | 처리 상태 조회 |
| - | `GET` | `/api/v1/floors/{floorId}/preview/{jobId}/{imageType}` | 미리보기 이미지 |
| 7 | `GET` | `/api/v1/floors/{floorId}/pointcloud` | PLY 다운로드 |
