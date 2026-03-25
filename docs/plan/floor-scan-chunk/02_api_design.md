# 02. API 설계

> **Backlink:** [00_master_plan.md](./00_master_plan.md)
> **Status:** Proposed
> **Last Updated:** 2026-03-24

---

## 1. Problem Context

기존 API는 건물 단위 스캔을 전제로 설계되어 있다. 층별 청크 분할 업로드/병합 체계로 전환하면서 URL 구조, 요청/응답 DTO, 컨트롤러 계층을 재설계해야 한다.

### 제약 조건

- RESTful URL 설계 (자원 계층 반영)
- DTO는 record 활용
- Validation은 DTO 레벨에서 jakarta.validation 사용
- 기존 API와의 하위 호환을 위한 전환 기간 고려

---

## 2. URL 구조 변경

### AS-IS

```
POST   /api/v1/buildings/{buildingId}/scans                  # 스캔 업로드
GET    /api/v1/buildings/{buildingId}/scans                  # 스캔 목록
GET    /api/v1/buildings/{buildingId}/scans/{sessionId}      # 스캔 상세
POST   /api/v1/buildings/{buildingId}/process                # 처리 시작
POST   /api/v1/buildings/{buildingId}/localize               # 위치 추정
```

### TO-BE

```
# 청크 관리
POST   /api/v1/floors/{floorId}/scans/chunks                        # 청크 업로드
GET    /api/v1/floors/{floorId}/scans/chunks                        # 청크 목록 조회
DELETE /api/v1/floors/{floorId}/scans/chunks/{chunkId}              # 청크 삭제
PUT    /api/v1/floors/{floorId}/scans/chunks/{chunkId}/replace      # 청크 교체

# 병합
POST   /api/v1/floors/{floorId}/scans/merge                         # 병합 트리거
GET    /api/v1/floors/{floorId}/scans/merge/status                  # 병합 상태 조회

# 처리
POST   /api/v1/floors/{floorId}/scans/process                       # 처리 트리거 (병합 결과 기반)
GET    /api/v1/floors/{floorId}/scans/process/status                # 처리 상태 조회
GET    /api/v1/floors/{floorId}/scans/pointcloud                    # PLY 다운로드

# 위치 추정 (건물 단위 유지 - 전체 층 병렬 매칭)
POST   /api/v1/buildings/{buildingId}/localize                      # 위치 추정

# 수직통로 (기존 조회 + CRUD 추가)
GET    /api/v1/buildings/{buildingId}/passages                      # 목록
POST   /api/v1/buildings/{buildingId}/passages                      # 생성
GET    /api/v1/passages/{passageId}                                 # 상세
PUT    /api/v1/passages/{passageId}                                 # 수정
DELETE /api/v1/passages/{passageId}                                 # 삭제
```

---

## 3. API 상세 설계

### 3.1 청크 업로드

```
POST /api/v1/floors/{floorId}/scans/chunks
Content-Type: multipart/form-data
```

**Request:**

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| floorId | UUID (path) | Y | 대상 층 ID |
| file | MultipartFile | Y | RTAB-Map .db 파일 (부분 스캔) |

**Response:** `201 Created`

```json
{
  "id": "uuid",
  "floorId": "uuid",
  "fileName": "scan_1f_part1.db",
  "fileSize": 1048576,
  "status": "UPLOADED",
  "active": true,
  "uploadOrder": 1,
  "createdAt": "2026-03-24T10:00:00"
}
```

**DTO:**

```
record ChunkUploadResponse(
    UUID id,
    UUID floorId,
    String fileName,
    Long fileSize,
    ChunkStatus status,
    boolean active,
    int uploadOrder,
    LocalDateTime createdAt
)
```

**완료 조건:**
- Floor 존재 여부 검증
- .db 확장자 검증
- 파일 저장 후 ScanChunk 생성 (active = true, uploadOrder = 기존 최대값 + 1)
- 이 시점에서는 병합/처리 트리거 안 함
- 같은 층에 여러 번 호출 가능 (청크 축적)

---

### 3.2 청크 목록 조회

```
GET /api/v1/floors/{floorId}/scans/chunks
```

**Response:** `200 OK`

```json
{
  "floorId": "uuid",
  "chunks": [
    {
      "id": "uuid-1",
      "fileName": "scan_1f_part1.db",
      "fileSize": 1048576,
      "status": "UPLOADED",
      "active": true,
      "uploadOrder": 1,
      "createdAt": "2026-03-24T10:00:00"
    },
    {
      "id": "uuid-2",
      "fileName": "scan_1f_part2.db",
      "fileSize": 2097152,
      "status": "UPLOADED",
      "active": true,
      "uploadOrder": 2,
      "createdAt": "2026-03-24T10:05:00"
    }
  ],
  "activeChunkCount": 2,
  "mergedScan": {
    "id": "uuid",
    "status": "COMPLETED",
    "plyFileId": "ply-cache-key",
    "sourceChunkCount": 2,
    "createdAt": "2026-03-24T10:10:00"
  }
}
```

**DTO:**

```
record FloorScanOverviewResponse(
    UUID floorId,
    List<ChunkSummary> chunks,
    int activeChunkCount,
    MergedScanSummary mergedScan
)

record ChunkSummary(
    UUID id,
    String fileName,
    Long fileSize,
    ChunkStatus status,
    boolean active,
    int uploadOrder,
    LocalDateTime createdAt
)

record MergedScanSummary(
    UUID id,
    MergedScanStatus status,
    String plyFileId,
    int sourceChunkCount,
    String errorMessage,
    LocalDateTime createdAt
)
```

---

### 3.3 청크 삭제

```
DELETE /api/v1/floors/{floorId}/scans/chunks/{chunkId}
```

**Request:** Body 없음

**Response:** `204 No Content`

**처리 흐름:**
1. ScanChunk 존재 및 floor 소속 검증
2. ScanChunk 삭제 (파일도 함께 삭제)
3. MergedScan이 존재하는 경우, 해당 병합 결과는 더 이상 최신이 아님을 표시 (관리자가 재병합 필요)

**완료 조건:**
- 병합 진행 중(MERGING)인 경우 삭제 거부
- 삭제 후 active 청크가 0개가 되면 기존 MergedScan 무효화

---

### 3.4 청크 교체

```
PUT /api/v1/floors/{floorId}/scans/chunks/{chunkId}/replace
Content-Type: multipart/form-data
```

**Request:**

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| floorId | UUID (path) | Y | 대상 층 ID |
| chunkId | UUID (path) | Y | 교체 대상 청크 ID |
| file | MultipartFile | Y | 새 .db 파일 |

**Response:** `200 OK`

```json
{
  "previousChunkId": "uuid",
  "newChunk": {
    "id": "uuid",
    "fileName": "scan_1f_part1_v2.db",
    "fileSize": 1100000,
    "status": "UPLOADED",
    "active": true,
    "uploadOrder": 1,
    "createdAt": "2026-03-24T11:00:00"
  },
  "message": "Chunk replaced. Re-merge required."
}
```

**처리 흐름:**
1. 기존 ScanChunk 비활성화 (active = false)
2. 새 ScanChunk 생성 (동일 uploadOrder 부여)
3. 기존 MergedScan은 더 이상 최신이 아님 (관리자가 재병합 필요)

**완료 조건:**
- 교체된 청크의 uploadOrder를 유지하여 병합 순서 보장
- 기존 청크 파일은 비활성 상태로 보존 (이력 관리)
- 병합 진행 중 교체 요청 시 거부

---

### 3.5 병합 트리거

```
POST /api/v1/floors/{floorId}/scans/merge
```

**Request:** Body 없음

**Response:** `200 OK`

```json
{
  "mergedScanId": "uuid",
  "status": "MERGING",
  "sourceChunkCount": 3,
  "message": "Merge started for 3 active chunks."
}
```

**처리 흐름:**
1. 해당 Floor의 active 청크 목록 조회 (uploadOrder 순)
2. 청크가 0개이면 에러 반환
3. 청크가 1개이면 병합 스킵 -> 해당 청크를 곧바로 MergedScan으로 사용 (status: MERGED)
4. 청크가 2개 이상이면:
   - 기존 MergedScan이 있으면 교체 준비
   - MergedScan 생성 (status: MERGING)
   - Python 서비스에 rtabmap-reprocess 비동기 요청
5. 병합 완료 시 MergedScan status -> MERGED

**단일 청크 최적화:**
```
if (activeChunks.size() == 1) {
    // 병합 스킵: 청크 파일을 MergedScan 파일로 직접 사용
    mergedScan.completeMerging(chunk.filePath, [chunk.id])
    // status: MERGED (MERGING 단계 스킵)
}
```

**완료 조건:**
- active 청크가 1개 이상이어야 병합 가능
- 이미 MERGING 상태인 MergedScan이 있으면 거부 (중복 병합 방지)
- 병합 성공 시 MergedScanMergedEvent 발행
- 병합 실패 시 MERGE_FAILED + 에러 메시지 (어떤 청크 쌍 연결 실패인지)

---

### 3.6 병합 상태 조회

```
GET /api/v1/floors/{floorId}/scans/merge/status
```

**Response:** `200 OK`

```json
{
  "mergedScanId": "uuid",
  "status": "MERGING",
  "progress": 45,
  "sourceChunkCount": 3,
  "errorMessage": null
}
```

또는 병합 실패 시:

```json
{
  "mergedScanId": "uuid",
  "status": "MERGE_FAILED",
  "progress": 0,
  "sourceChunkCount": 3,
  "errorMessage": "Failed to find enough correspondences between chunk_1 and chunk_3. Ensure overlapping regions exist."
}
```

---

### 3.7 처리 트리거 (병합 결과 기반)

```
POST /api/v1/floors/{floorId}/scans/process
```

**Request:** Body 없음

**Response:** `200 OK`

```json
{
  "mergedScanId": "uuid",
  "jobId": "python-job-uuid",
  "status": "EXTRACTING"
}
```

**처리 흐름:**
1. 해당 Floor의 MergedScan 조회
2. MergedScan status가 MERGED인지 확인 (아니면 거부)
3. MergedScan의 .db 파일을 Python 서비스에 업로드 + 처리 시작
4. MergedScan status -> EXTRACTING
5. 비동기로 처리 완료 대기 + 결과 적용
6. 완료 시 FloorPath + PathSegment 생성, PLY 추출
7. MergedScan status -> COMPLETED
8. ScanProcessingCompletedEvent 발행 (VPS 등록 트리거)

**완료 조건:**
- MERGED 상태의 MergedScan이 없으면 거부 (먼저 병합 필요)
- 처리 중(EXTRACTING/PROCESSING) 상태에서 재요청 시 거부
- 기존 FloorPath/PathSegment 삭제 후 재생성
- 처리 완료 시 Floor.plyFileId 갱신

---

### 3.8 처리 상태 조회

```
GET /api/v1/floors/{floorId}/scans/process/status
```

**Response:** `200 OK`

```json
{
  "mergedScanId": "uuid",
  "status": "PROCESSING",
  "progress": 70,
  "plyFileId": null,
  "vpsMapId": null
}
```

---

### 3.9 PLY 다운로드

```
GET /api/v1/floors/{floorId}/scans/pointcloud
```

**Response:** PLY 파일 스트림 또는 캐시 키 반환

**완료 조건:**
- MergedScan이 COMPLETED 상태이고 plyFileId가 존재해야 함
- plyFileId가 없으면 404

---

### 3.10 VPS 위치 추정 (변경)

```
POST /api/v1/buildings/{buildingId}/localize
Content-Type: multipart/form-data
```

**Request:**

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| buildingId | UUID (path) | Y | 건물 ID |
| images | List<MultipartFile> | Y | 위치 추정용 이미지 (1장 이상) |

**Response:** `200 OK`

```json
{
  "floorId": "uuid",
  "floorName": "1층",
  "floorLevel": 1,
  "pose": {
    "x": 1.23,
    "y": 4.56,
    "z": 0.78,
    "rotation": [...]
  },
  "confidence": 0.85,
  "numMatches": 42,
  "allFloorResults": [
    {
      "floorId": "uuid-1f",
      "floorLevel": 1,
      "confidence": 0.85,
      "numMatches": 42
    },
    {
      "floorId": "uuid-2f",
      "floorLevel": 2,
      "confidence": 0.12,
      "numMatches": 3
    }
  ]
}
```

**DTO:**

```
record FloorLocalizeResult(
    UUID floorId,
    int floorLevel,
    Double confidence,
    Integer numMatches,
    Map<String, Object> pose
)

record BuildingLocalizeResponse(
    UUID floorId,
    String floorName,
    int floorLevel,
    Map<String, Object> pose,
    Double confidence,
    Integer numMatches,
    List<FloorLocalizeResult> allFloorResults
)
```

**처리 흐름:**
1. 건물의 모든 Floor 중 MergedScan의 vpsMapId가 있는 층 목록 조회
2. 각 층에 대해 VPS localize 병렬 호출 (CompletableFuture)
3. confidence 기준 최고 점수 층 선택
4. 최고 점수 층의 결과를 메인으로, 나머지를 allFloorResults에 포함

**완료 조건:**
- vpsMapId가 없는 층은 스킵
- 모든 층이 실패하면 LOCALIZATION_FAILED 예외
- 개별 층 VPS 호출 실패는 무시 (나머지 층 결과로 응답)
- timeout 설정 (층당 10초)

---

### 3.11 수직통로 CRUD

#### 생성

```
POST /api/v1/buildings/{buildingId}/passages
Content-Type: application/json
```

**Request:**

```json
{
  "type": "STAIRS",
  "name": "본관 중앙 계단",
  "fromFloorId": "uuid",
  "toFloorId": "uuid",
  "entryX": 1.0,
  "entryY": 2.0,
  "entryZ": 0.0,
  "exitX": 1.0,
  "exitY": 2.0,
  "exitZ": 3.5
}
```

**DTO:**

```
record PassageCreateRequest(
    @NotNull PassageType type,
    @NotBlank String name,
    @NotNull UUID fromFloorId,
    @NotNull UUID toFloorId,
    @NotNull Double entryX,
    @NotNull Double entryY,
    @NotNull Double entryZ,
    @NotNull Double exitX,
    @NotNull Double exitY,
    @NotNull Double exitZ
)
```

**Response:** `201 Created`

#### 수정

```
PUT /api/v1/passages/{passageId}
```

동일한 필드 구조. 부분 업데이트(entry/exit 좌표만 수정 등)를 위해 nullable 필드 허용.

#### 삭제

```
DELETE /api/v1/passages/{passageId}
```

**처리 흐름:**
1. 해당 Passage에 연결된 PathNode(PASSAGE_ENTRY/EXIT) 정리
2. PathEdge(VERTICAL_*) 정리
3. VerticalPassage 삭제

---

## 4. 변경 전후 API 대조표

| 기능 | AS-IS | TO-BE | 비고 |
|------|-------|-------|------|
| 스캔 업로드 | POST /buildings/{bid}/scans | POST /floors/{fid}/scans/chunks | 청크 단위 |
| 스캔 목록 | GET /buildings/{bid}/scans | GET /floors/{fid}/scans/chunks | 청크 목록 + 병합 상태 |
| 처리 시작 | POST /buildings/{bid}/process | POST /floors/{fid}/scans/process | MergedScan 기반 |
| 병합 트리거 | (없음) | POST /floors/{fid}/scans/merge | 신규 |
| 병합 상태 | (없음) | GET /floors/{fid}/scans/merge/status | 신규 |
| 청크 교체 | (없음) | PUT /floors/{fid}/scans/chunks/{cid}/replace | 신규 |
| 청크 삭제 | (없음) | DELETE /floors/{fid}/scans/chunks/{cid} | 신규 |
| 처리 상태 | (없음) | GET /floors/{fid}/scans/process/status | 신규 |
| PLY 다운로드 | GET /scans/{sid}/pointcloud | GET /floors/{fid}/scans/pointcloud | MergedScan 기반 |
| 위치 추정 | POST /buildings/{bid}/localize | POST /buildings/{bid}/localize | 응답 확장 |
| Passage 생성 | (없음) | POST /buildings/{bid}/passages | 신규 |
| Passage 수정 | (없음) | PUT /passages/{pid} | 신규 |
| Passage 삭제 | (없음) | DELETE /passages/{pid} | 신규 |

---

## 5. 에러 코드 추가

| 코드 | HTTP | 메시지 | 사용처 |
|------|------|--------|--------|
| SC001 | 400 Bad Request | Invalid file extension, only .db files are accepted | 청크 업로드 시 |
| SC002 | 404 Not Found | Scan chunk not found | 청크 조회/삭제 시 |
| SC003 | 409 Conflict | Cannot delete chunk while merge is in progress | 병합 중 청크 삭제 시도 |
| SC004 | 409 Conflict | Cannot replace chunk while merge is in progress | 병합 중 청크 교체 시도 |
| MS001 | 400 Bad Request | No active chunks available for merge | 병합 대상 청크 없음 |
| MS002 | 409 Conflict | Merge is already in progress | 중복 병합 요청 |
| MS003 | 400 Bad Request | Merge failed due to insufficient overlap between chunks | overlap 부족 |
| MS004 | 400 Bad Request | No merged scan available, merge first | 병합 없이 처리 시도 |
| MS005 | 400 Bad Request | Merged scan is not in MERGED state, cannot process | 비정상 상태에서 처리 시도 |
| MS006 | 409 Conflict | Processing is already in progress | 중복 처리 요청 |
| V003 | 404 Not Found | No VPS map registered for any floor | VPS 맵 없는 건물 위치 추정 |
| VP001 | 404 Not Found | Vertical passage not found | 통로 미발견 |
| VP002 | 400 Bad Request | From and to floors must be different | 동일 층 통로 생성 시도 |

---

## 6. Controller 계층 구조

### scan 모듈

```
ScanController
  @RequestMapping("/api/v1/floors/{floorId}/scans")

  # 청크 관리
  - POST /chunks                       -> ChunkUploader.upload(floorId, file)
  - GET  /chunks                       -> ScanChunkReader.findByFloorId(floorId)
  - DELETE /chunks/{chunkId}            -> ChunkDeleter.delete(floorId, chunkId)
  - PUT  /chunks/{chunkId}/replace      -> ChunkReplacer.replace(floorId, chunkId, file)

  # 병합
  - POST /merge                         -> ChunkMerger.merge(floorId)
  - GET  /merge/status                  -> MergedScanReader.getMergeStatus(floorId)

  # 처리
  - POST /process                       -> ProcessingStarter.start(floorId)
  - GET  /process/status                -> MergedScanReader.getProcessStatus(floorId)

  # PLY
  - GET  /pointcloud                    -> PlyDownloader.download(floorId)
```

### localization 모듈

```
LocalizationController
  @RequestMapping("/api/v1/buildings/{buildingId}")
  - POST /localize  -> LocalizationService.localizeAcrossFloors(buildingId, images)
```

### passage 모듈

```
PassageController
  - GET    /api/v1/buildings/{buildingId}/passages  -> PassageReader.findByBuildingId()
  - POST   /api/v1/buildings/{buildingId}/passages  -> PassageCreator.create()
  - GET    /api/v1/passages/{passageId}             -> PassageReader.findById()
  - PUT    /api/v1/passages/{passageId}             -> PassageUpdater.update()
  - DELETE /api/v1/passages/{passageId}             -> PassageDeleter.delete()
```

---

## 7. Milestones

| 단계 | 작업 | 검증 방법 |
|------|------|----------|
| 7.1 | Request/Response DTO record 작성 | 컴파일 확인 |
| 7.2 | ScanController URL 변경 + 청크 CRUD 엔드포인트 | E2E 테스트 |
| 7.3 | 병합 트리거/상태 조회 엔드포인트 | E2E 테스트 |
| 7.4 | 처리 트리거/상태 조회 엔드포인트 | E2E 테스트 |
| 7.5 | Application 서비스 (ChunkUploader, ChunkMerger, ChunkReplacer, ChunkDeleter) 작성 | 단위 테스트 |
| 7.6 | LocalizationService 병렬 매칭 구현 | 단위 테스트 |
| 7.7 | PassageController CRUD 추가 | E2E 테스트 |

---

## 8. Risks & Constraints

| 리스크 | 대응 |
|--------|------|
| 기존 프론트엔드가 /buildings/{bid}/scans 호출 중 | 전환 기간 동안 deprecated API 유지, 05_migration 참조 |
| 병렬 VPS 호출 시 VPS 서비스 부하 | 층 수가 제한적(보통 3-10개)이므로 문제없을 것으로 판단. timeout 설정 |
| 대용량 청크 파일 업로드 시 메모리 | Spring multipart 설정에서 file-size-threshold 조정, 스트리밍 저장 |
| 병합 중 청크 수정/삭제 요청 | MERGING 상태에서는 청크 수정/삭제 거부 (409 Conflict) |
| 동시에 여러 관리자가 같은 층에 청크 업로드 | uploadOrder 동시성 제어 필요. @Transactional + 비관적 락 또는 DB sequence |
