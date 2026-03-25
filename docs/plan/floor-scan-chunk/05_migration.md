# 05. 마이그레이션 전략

> **Backlink:** [00_master_plan.md](./00_master_plan.md)
> **Status:** Proposed
> **Last Updated:** 2026-03-24

---

## 1. Problem Context

층별 청크 분할 업로드/병합 체계로 전환하면서 다음 요소의 마이그레이션이 필요하다.

| 대상 | 현재 | 변경 후 | 마이그레이션 필요 |
|------|------|---------|------------------|
| ScanSession | Building 소속 엔티티 | **제거** -> ScanChunk + MergedScan으로 대체 | 테이블 신규 생성 + 데이터 이전 |
| ScanChunk | 없음 | Floor 소속, 부분 스캔 파일 | 테이블 신규 생성 |
| MergedScan | 없음 | Floor 소속, 병합 결과 | 테이블 신규 생성 |
| Floor.vpsMapId | 없음 | 제거 (MergedScan으로 이동) | - |
| Building.scanSessions | OneToMany | 제거 | 외래키 제거 |
| VerticalPassage.pathGeometry | 존재 | 제거 | 컬럼 drop |
| VerticalPassage.segments | OneToMany | 제거 | 관계 해제 + 데이터 삭제 |
| VerticalPassage.name | 없음 | String 추가 | 스키마 추가 |
| API URL | /buildings/{bid}/scans | /floors/{fid}/scans/chunks | 프론트엔드 전환 |

### 제약 조건

- 무중단 전환은 불필요 (졸업 프로젝트 운영 환경)
- Flyway 마이그레이션 스크립트 사용
- 기존 데이터 최대한 보존
- 전환 기간 동안 deprecated API 지원 검토

---

## 2. Solution Options

### Option A: Big Bang (한 번에 전환) - 선택

모든 변경을 한 번에 적용한다. 기존 API는 즉시 폐기.

| 장점 | 단점 |
|------|------|
| 구현 단순 | 롤백 어려움 |
| deprecated 코드 관리 불필요 | 프론트엔드 동시 업데이트 필요 |
| 코드베이스 깔끔 | - |

### Option B: 점진적 전환 (deprecated API 병행)

기존 API를 deprecated로 유지하면서 새 API를 추가. 전환 기간 후 삭제.

| 장점 | 단점 |
|------|------|
| 안전한 전환 | 두 벌의 코드 유지 비용 |
| 프론트엔드 독립 업데이트 | 복잡도 증가 |

**결정: Option A** - 졸업 프로젝트 특성상 프론트엔드/백엔드를 동일 팀이 관리하므로 동시 전환이 효율적.

---

## 3. DB 마이그레이션 계획

### 3.1 Flyway 스크립트 순서

```
V__XX__chunk_merge_scan_migration.sql
```

#### Step 1: 신규 테이블 생성

```sql
-- ScanChunk 테이블 생성
CREATE TABLE scan_chunks (
    id UUID PRIMARY KEY,
    floor_id UUID NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    file_size BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'UPLOADED',
    active BOOLEAN NOT NULL DEFAULT true,
    upload_order INT NOT NULL,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_scan_chunks_floor FOREIGN KEY (floor_id) REFERENCES floors(id)
);

-- MergedScan 테이블 생성
CREATE TABLE merged_scans (
    id UUID PRIMARY KEY,
    floor_id UUID NOT NULL UNIQUE,
    file_path VARCHAR(500),
    ply_file_id VARCHAR(255),
    vps_map_id VARCHAR(255),
    status VARCHAR(20) NOT NULL,
    source_chunk_ids TEXT,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_merged_scans_floor FOREIGN KEY (floor_id) REFERENCES floors(id),
    CONSTRAINT uq_merged_scans_floor UNIQUE (floor_id)
);
```

#### Step 2: 기존 ScanSession 데이터 마이그레이션

```sql
-- 기존 ScanSession을 ScanChunk로 변환
-- ScanSession이 Building에 소속되어 있으므로, 해당 Building의 첫 번째 Floor로 매핑

-- Floor가 없는 Building의 ScanSession 처리: 1층 Floor 자동 생성
INSERT INTO floors (id, building_id, name, level, created_at, updated_at)
SELECT
    gen_random_uuid(),
    ss.building_id,
    '1층',
    1,
    NOW(),
    NOW()
FROM scan_sessions ss
LEFT JOIN floors f ON f.building_id = ss.building_id
WHERE f.id IS NULL
GROUP BY ss.building_id;

-- ScanSession -> ScanChunk 변환
INSERT INTO scan_chunks (id, floor_id, file_name, file_path, file_size, status, active, upload_order, error_message, created_at, updated_at)
SELECT
    ss.id,
    (SELECT f.id FROM floors f WHERE f.building_id = ss.building_id ORDER BY f.level ASC LIMIT 1),
    ss.file_name,
    ss.file_path,
    ss.file_size,
    'UPLOADED',
    true,
    ROW_NUMBER() OVER (PARTITION BY ss.building_id ORDER BY ss.created_at),
    NULL,
    ss.created_at,
    ss.updated_at
FROM scan_sessions ss;

-- COMPLETED 상태의 ScanSession에 대해 MergedScan 생성
INSERT INTO merged_scans (id, floor_id, file_path, ply_file_id, vps_map_id, status, source_chunk_ids, created_at, updated_at)
SELECT
    gen_random_uuid(),
    sc.floor_id,
    ss.file_path,
    ss.ply_file_id,
    NULL,
    'COMPLETED',
    CONCAT('["', sc.id::text, '"]'),
    ss.created_at,
    ss.updated_at
FROM scan_sessions ss
JOIN scan_chunks sc ON sc.id = ss.id
WHERE ss.status = 'COMPLETED'
AND ss.id = (
    SELECT s2.id FROM scan_sessions s2
    WHERE s2.building_id = ss.building_id
    AND s2.status = 'COMPLETED'
    ORDER BY s2.created_at DESC
    LIMIT 1
);
```

#### Step 3: VerticalPassage 변경

```sql
-- VerticalPassage에 name 추가
ALTER TABLE vertical_passages ADD COLUMN name VARCHAR(255);

-- VerticalPassage name 기본값 설정
UPDATE vertical_passages
SET name = CONCAT(
    CASE type WHEN 'STAIRS' THEN '계단' WHEN 'ELEVATOR' THEN '엘리베이터' END,
    ' (', id::text, ')'
)
WHERE name IS NULL;

-- VerticalPassage의 segments 관계 해제
DELETE FROM path_segments WHERE vertical_passage_id IS NOT NULL;

-- VerticalPassage pathGeometry 컬럼 제거
ALTER TABLE vertical_passages DROP COLUMN IF EXISTS path_geometry;
```

#### Step 4: 기존 테이블 정리

```sql
-- ScanSession 테이블의 Building 외래키 제거
ALTER TABLE scan_sessions DROP CONSTRAINT IF EXISTS fk_scan_sessions_building;

-- ScanSession 테이블 제거 (데이터는 이미 scan_chunks로 이전됨)
-- 주의: 다른 테이블에서 scan_sessions를 참조하는 외래키가 없는지 확인
DROP TABLE IF EXISTS scan_sessions;
```

---

## 4. 코드 변경 순서

전체 변경을 단일 브랜치에서 작업하되, 커밋 단위는 논리적으로 분리한다.

### Phase 1: 도메인 모델 변경 (M1)

```
1. ScanChunk.java 신규 작성 (floor 참조, active, uploadOrder, 도메인 메서드)
2. MergedScan.java 신규 작성 (floor 참조, 상태 머신, 도메인 메서드)
3. Floor.java 변경 (scanChunks 컬렉션, mergedScan 필드, 도메인 메서드)
4. Building.java 변경 (scanSessions 관계 제거)
5. VerticalPassage.java 변경 (segments, pathGeometry 제거, name 추가)
6. ScanChunkRepository.java 신규 작성
7. MergedScanRepository.java 신규 작성
8. ScanSession.java, ScanSessionRepository.java 제거
9. Flyway 마이그레이션 스크립트 작성
10. 기존 테스트 수정 및 통과 확인
```

### Phase 2: 청크 관리 + 병합 (M2)

```
1. ChunkUploader.java 신규 작성 (청크 업로드 서비스)
2. ChunkDeleter.java 신규 작성 (청크 삭제 서비스)
3. ChunkReplacer.java 신규 작성 (청크 교체 서비스)
4. ScanChunkReader.java 신규 작성 (청크 조회 서비스)
5. ChunkMerger.java 신규 작성 (병합 서비스, 단일 청크 스킵 로직 포함)
6. MergedScanReader.java 신규 작성 (병합 상태 조회 서비스)
7. ScanController.java 전체 재작성 (청크/병합/처리 엔드포인트)
8. Python 서비스 merge 엔드포인트 연동 코드 작성
9. 기존 ScanFileUploader.java 제거
```

### Phase 3: 처리 파이프라인 변경 (M3)

```
1. ProcessingStarter.java 변경 (MergedScan 기반 처리, floorId 기반)
2. ProcessingResultApplier.java 변경 (applyToFloor, 자동 감지 제거)
3. FloorDataCleaner.java 신규 작성
4. ScanProcessingCompletedEvent.java 변경 (mergedScanId 포함)
5. ChunksMergedEvent.java 신규 작성
6. FloorScanReprocessedEvent.java 신규 작성
7. PLY 추출 단순화
```

### Phase 4: VPS 변경 (M4)

```
1. VpsClient.java 변경 (processSlamForFloor)
2. LocalizationService.java 변경 (localizeAcrossFloors, MergedScan.vpsMapId 참조)
3. ScanProcessingCompletedEventListener.java 변경 (MergedScan 기반 VPS 등록)
4. LocalizationController.java 응답 변경
5. 기존 ScanFileUploadedEventListener.java 제거
```

### Phase 5: 수직통로 (M5)

```
1. PassageCreator.java 신규 작성
2. PassageUpdater.java 신규 작성
3. PassageDeleter.java 신규 작성
4. PassageController.java CRUD 엔드포인트 추가
5. PassageNodeConnector.java 신규 작성
6. PassageEventListener.java 신규 작성 (pathfinding 모듈)
7. FloorScanReprocessedEventListener.java 신규 작성
```

---

## 5. 테스트 마이그레이션

### 제거 대상 기존 테스트

| 테스트 파일 | 제거 이유 |
|------------|----------|
| ScanSession 관련 도메인 테스트 | ScanChunk, MergedScan 테스트로 대체 |
| ScanSessionRepository 테스트 | ScanChunkRepository, MergedScanRepository 테스트로 대체 |
| ScanFileUploader 테스트 | ChunkUploader 테스트로 대체 |

### 수정 필요한 기존 테스트

| 테스트 파일 | 변경 내용 |
|------------|----------|
| BuildingControllerE2ETest.java | scanSessions 관련 검증 제거 |
| FloorControllerE2ETest.java | scanChunks, mergedScan 관련 검증 추가 |
| ProcessingControllerE2ETest.java | URL 경로 변경, MergedScan 기반으로 전환 |
| AStarPathfinderTest.java | 수직통로 연결 방식 변경 반영 |

### 신규 테스트

| 테스트 | 검증 대상 |
|--------|----------|
| ScanChunk 도메인 테스트 | activate/deactivate 로직 |
| MergedScan 도메인 테스트 | 상태 머신 전환 (MERGING -> MERGED -> EXTRACTING -> COMPLETED 등) |
| Floor 도메인 테스트 | addScanChunk/getActiveChunks/canMerge/replaceMergedScan 로직 |
| ChunkUploader 서비스 테스트 | 업로드 흐름 + uploadOrder 자동 설정 |
| ChunkMerger 서비스 테스트 | 단일 청크 스킵 + 다중 청크 병합 + 실패 처리 |
| ChunkReplacer 서비스 테스트 | 교체 흐름 + 기존 청크 비활성화 |
| ChunkDeleter 서비스 테스트 | 삭제 흐름 + 병합 중 거부 |
| FloorDataCleaner 통합 테스트 | DB 데이터 정리 검증 |
| ProcessingStarter 서비스 테스트 | MergedScan 기반 처리 흐름 |
| LocalizationService 단위 테스트 | 병렬 매칭 + 최고 confidence 선택 |
| PassageCreator 서비스 테스트 | 생성 + 이벤트 발행 |
| PassageNodeConnector 통합 테스트 | 노드/엣지 자동 생성 |
| ScanController E2E 테스트 | 전체 플로우 (청크 업로드 -> 병합 -> 처리) |

---

## 6. 롤백 계획

Big Bang 전환이므로 롤백 시나리오를 명시한다.

### DB 롤백

```sql
-- Flyway undo 또는 수동 롤백

-- ScanSession 테이블 복원
CREATE TABLE scan_sessions (
    id UUID PRIMARY KEY,
    building_id UUID NOT NULL,
    file_name VARCHAR(255),
    file_path VARCHAR(500),
    file_size BIGINT,
    status VARCHAR(20),
    ply_file_id VARCHAR(255),
    error_message TEXT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT fk_scan_sessions_building FOREIGN KEY (building_id) REFERENCES buildings(id)
);

-- ScanChunk 데이터를 ScanSession으로 복원
INSERT INTO scan_sessions (id, building_id, file_name, file_path, file_size, status, ply_file_id, created_at, updated_at)
SELECT
    sc.id,
    f.building_id,
    sc.file_name,
    sc.file_path,
    sc.file_size,
    'UPLOADED',
    NULL,
    sc.created_at,
    sc.updated_at
FROM scan_chunks sc
JOIN floors f ON f.id = sc.floor_id;

-- COMPLETED MergedScan이 있는 경우 해당 ScanSession의 상태를 COMPLETED로 갱신
UPDATE scan_sessions ss
SET status = 'COMPLETED',
    ply_file_id = ms.ply_file_id
FROM merged_scans ms
JOIN scan_chunks sc ON sc.floor_id = ms.floor_id AND sc.active = true
WHERE ss.id = sc.id
AND ms.status = 'COMPLETED';

-- 신규 테이블 제거
DROP TABLE IF EXISTS merged_scans;
DROP TABLE IF EXISTS scan_chunks;

-- VerticalPassage 정리
ALTER TABLE vertical_passages DROP COLUMN IF EXISTS name;
-- pathGeometry 복원은 불가 (데이터 손실)
```

### 코드 롤백

Git revert로 전체 브랜치 변경을 되돌린다.

---

## 7. 전환 체크리스트

```
[ ] Phase 1 완료 - 도메인 모델 변경 (ScanChunk, MergedScan), Flyway 적용, 기존 테스트 통과
[ ] Phase 2 완료 - 청크 업로드/삭제/교체/병합 동작 확인
[ ] Phase 3 완료 - 처리 파이프라인 변경, MergedScan 기반 처리 동작 확인
[ ] Phase 4 완료 - VPS 층별 등록, 병렬 위치 추정 동작 확인
[ ] Phase 5 완료 - 수직통로 CRUD, 그래프 연결 동작 확인
[ ] 프론트엔드 API 호출 경로 업데이트 완료
[ ] 전체 E2E 테스트 통과
[ ] 로컬 환경에서 end-to-end 시나리오 검증
    [ ] 건물 생성 -> 층 생성 -> 청크 업로드 (여러 개)
    [ ] 병합 트리거 -> 병합 완료 확인
    [ ] 처리 트리거 -> FloorPath 생성 + PLY 추출 확인
    [ ] 단일 청크 업로드 -> 병합 스킵 -> 처리 성공
    [ ] 청크 교체 -> 재병합 -> 재처리 -> 기존 데이터 갱신
    [ ] 병합 실패 시 에러 메시지 확인
    [ ] 수직통로 생성 -> 경로 탐색에 반영
    [ ] 위치 추정 -> 올바른 층 식별
```

---

## 8. Milestones

| 단계 | 작업 | 검증 방법 | 예상 소요 |
|------|------|----------|----------|
| 8.1 | Flyway 마이그레이션 스크립트 작성 | 로컬 DB 적용 + 데이터 검증 | 0.5일 |
| 8.2 | Phase 1 (도메인) 코드 변경 | 컴파일 + 기존 테스트 수정/통과 | 1.5일 |
| 8.3 | Phase 2 (청크 + 병합) 코드 변경 | 신규 서비스 테스트 + E2E | 2일 |
| 8.4 | Phase 3 (파이프라인) 코드 변경 | MergedScan 기반 처리 테스트 | 2일 |
| 8.5 | Phase 4 (VPS) 코드 변경 | 병렬 매칭 테스트 | 1.5일 |
| 8.6 | Phase 5 (수직통로) 코드 변경 | CRUD + 이벤트 테스트 | 1.5일 |
| 8.7 | 통합 테스트 + 시나리오 검증 | E2E 시나리오 전체 통과 | 1일 |

**총 예상 소요: 10일**

---

## 9. Risks & Constraints

| 리스크 | 발생 확률 | 영향 | 대응 |
|--------|----------|------|------|
| Flyway 마이그레이션 실패 (ScanSession -> ScanChunk/MergedScan 데이터 변환 복잡) | 중간 | 높음 | 로컬에서 충분히 테스트. DB 백업 후 적용 |
| 기존 데이터 중 floor 없는 ScanSession | 높음 | 중간 | 마이그레이션 스크립트에서 1층 자동 생성으로 해결 |
| ScanSession 테이블 제거 시 다른 코드에서 참조 | 중간 | 높음 | 사전 grep으로 모든 참조 파악. 컴파일 에러로 추가 확인 |
| pathGeometry 복원 불가 (롤백 한계) | 낮음 | 낮음 | 수동 설정으로 전환하므로 복원 필요성 없음 |
| 프론트엔드 동시 업데이트 누락 | 중간 | 높음 | 프론트엔드 변경 목록 사전 정리, 동시 배포 |
| 청크 파일 + 병합 파일 + 이전 파일 누적 | 중간 | 중간 | 마이그레이션 시 기존 .db 파일은 ScanChunk 파일로 간주. 이후 정리 정책 수립 |
