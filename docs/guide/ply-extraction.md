# PLY 포인트클라우드 추출 파이프라인

RTAB-Map .db 파일에서 웹 시각화용 PLY 파일을 추출하는 과정을 설명한다.

## 전체 흐름

```
스캔 청크 업로드 (.db)
        ↓
서버 병합 (rtabmap-reprocess)
        ↓
MergedScan 상태: MERGED
        ↓
ScanProcessingExecutor (비동기)
        ↓
Python 서비스: rtabmap-export --cloud
        ↓
PLY 헤더 동적 파싱 → xyz + rgb만 추출
        ↓
cache_key 반환 → Floor.plyFileId에 저장
        ↓
GET /api/v1/floors/{floorId}/pointcloud → PLY 바이트 다운로드
```

## rtabmap-export 옵션

| 파라미터 | 값 | 역할 |
|---------|-----|------|
| `--cloud` | — | 포인트클라우드 모드 |
| `--voxel` | 0.05 | 5cm 단위 복셀 필터. 너무 작으면 파일 거대, 너무 크면 색상 뭉개짐 |
| `--decimation` | 2 | 절반 해상도. 1=원본(파일 거대), 4=1/4(색상 손실) |
| `--max_range` | 4 | 4m 이내 포인트만 포함. 0=무제한(바닥/천장 노이즈 포함) |
| `--depth_confidence` | 20 | 신뢰도 20 이상만. 0=전부(노이즈 많음), 50=너무 적음 |

## 옵션 선택 과정

초기 설정(voxel=0.2, decimation=4)에서 실제 건물 색상과 다른 문제가 발생하여 단계적으로 조정하였다.

1. **최대 품질 시도** (voxel=0, decimation=1, max_range=0, depth_confidence=0)
   - 색상은 정확하지만 파일이 256MB 초과 → WebClient 버퍼 오류
   - 바닥/천장/노이즈 포인트가 복도 빈 공간을 채움
2. **현재 설정** (voxel=0.05, decimation=2, max_range=4, depth_confidence=20)
   - 색상 정확도 유지하면서 파일 크기 40MB 이내
   - 4m 범위 제한으로 빈 공간 노이즈 제거

## PLY 헤더 동적 파싱

`rtabmap-export` 버전이나 옵션에 따라 PLY 출력 포맷(프로퍼티 순서, 추가 데이터)이 달라진다. 하드코딩 대신 헤더를 동적으로 파싱한다.

```
PLY 원본 (rtabmap-export 출력)
  property float x, y, z
  property uchar red, green, blue
  property float nx, ny, nz        ← 법선 (제거)
  property float curvature         ← 곡률 (제거)
  element face 0                   ← 무시
  element camera 1                 ← 무시
        ↓
경량 PLY (웹용)
  property float x, y, z
  property uchar red, green, blue
```

- `element vertex`에 속한 프로퍼티만 파싱하여 dtype 구성
- `element face`, `element camera` 등은 건너뜀
- 색상 필드명 자동 매핑: `red`/`r`/`diffuse_red` 모두 대응

**파일:** `rtab/path_service/services/ply_extraction.py`

## 좌표계

| 시스템 | X | Y | Z |
|--------|---|---|---|
| RTAB-Map (API) | 좌우 | 전후 | 높이 |
| Three.js (웹) | 좌우(반전) | 높이 | 전후 |

- PLY 로딩 시 X축 반전 + Y↔Z 스왑 적용
- `apiToThree(p)` = `[-p.x, p.z, p.y]`
- `threeToApi(x, y, z)` = `{x: -x, y: z, z: y}`
