"""
=============================================================================
실내 경로 처리 서비스 (Indoor Path Processing Service)
=============================================================================

이 FastAPI 애플리케이션은 RTAB-Map DB 파일을 처리하여
실내 길찾기에 필요한 경로 데이터를 추출합니다.

[서비스 아키텍처]
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│  Spring Boot    │────▶│  Path Service   │────▶│  PostgreSQL     │
│  (메인 서버)    │     │  (이 서비스)    │     │  (pgRouting)    │
└─────────────────┘     └─────────────────┘     └─────────────────┘
         │                      │
         │                      ▼
         │              ┌───────────────┐
         └─────────────▶│  .db 파일     │
                        │  (RTAB-Map)   │
                        └───────────────┘

[처리 파이프라인]
1. DB 파일 업로드
2. 궤적 추출 (extraction)
3. 층 분리 & 수직통로 감지 (vertical_detector)
4. 중복 제거 (deduplication)
5. RDP + 직선 스냅 (path_flattening) - 경로 직선화
6. 갈림길 감지 & 그래프 추출 (junction_detection)
7. 결과 JSON 반환

[API 엔드포인트]
- POST /api/v1/upload              : DB 파일 업로드
- POST /api/v1/process/{file_id}   : 처리 시작 (비동기)
- GET  /api/v1/jobs/{job_id}       : 작업 상태 조회
- GET  /api/v1/jobs/{job_id}/result: 처리 결과 조회
- GET  /api/v1/preview/{job_id}/{type}: 미리보기 이미지

[환경 변수]
- UPLOAD_DIR: 업로드 디렉토리 (기본: ./uploads)
- OUTPUT_DIR: 출력 디렉토리 (기본: ./output)
"""

from fastapi import FastAPI, UploadFile, File, HTTPException, BackgroundTasks
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel
from typing import List, Optional, Dict
import os
import uuid
import asyncio
from datetime import datetime
import numpy as np

# 서비스 모듈 임포트
from services.extraction import extract_trajectory_from_db, get_trajectory_stats
from services.deduplication import deduplicate_path, merge_overlapping_segments
from services.smoothing import remove_outliers
from services.path_flattening import snap_to_lines
from services.vertical_detector import detect_stairs_first, separate_floors, assign_floors_to_stairs
from services.junction_detection import build_path_graph, get_graph_stats, merge_floor_graphs


# =============================================================================
# FastAPI 앱 초기화
# =============================================================================

tags_metadata = [
    {
        "name": "System",
        "description": "서비스 상태 확인 및 헬스체크",
    },
    {
        "name": "Upload",
        "description": "RTAB-Map .db 파일 업로드",
    },
    {
        "name": "Processing",
        "description": "경로 데이터 처리 (비동기 작업 관리)",
    },
    {
        "name": "Preview",
        "description": "처리 결과 미리보기 이미지",
    },
]

app = FastAPI(
    title="실내 경로 처리 서비스 (Indoor Path Processing Service)",
    description="""
    RTAB-Map DB 파일에서 실내 경로 데이터를 추출하고 처리하는 서비스입니다.

    ## 주요 기능
    - 카메라 궤적 추출
    - 경로 직선화 (카메라 흔들림 보정)
    - 갈림길 감지 및 그래프 구축
    - 계단/엘리베이터 감지
    - 층 분리

    ## 사용 방법
    1. `/api/v1/upload`로 .db 파일 업로드
    2. `/api/v1/process/{file_id}`로 처리 시작
    3. `/api/v1/jobs/{job_id}`로 진행 상황 확인
    4. `/api/v1/jobs/{job_id}/result`로 결과 조회
    """,
    version="1.0.0",
    contact={
        "name": "KoreaTech Indoor Pathfinding Team",
        "url": "https://github.com/20HyeonsuLee/indoor-pathfinding-backend",
    },
    openapi_tags=tags_metadata,
    docs_url="/docs",
    redoc_url="/redoc",
)

# CORS 설정
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# =============================================================================
# 설정
# =============================================================================

# 환경 변수에서 디렉토리 경로 로드
UPLOAD_DIR = os.getenv("UPLOAD_DIR", "./uploads")
OUTPUT_DIR = os.getenv("OUTPUT_DIR", "./output")

# 디렉토리 생성 (없으면)
os.makedirs(UPLOAD_DIR, exist_ok=True)
os.makedirs(OUTPUT_DIR, exist_ok=True)

# 작업 저장소 (메모리 기반 - 프로덕션에서는 Redis 등 사용 권장)
processing_jobs: Dict[str, "ProcessingJob"] = {}


# =============================================================================
# 데이터 모델 (Pydantic)
# =============================================================================

class ProcessingJob(BaseModel):
    """처리 작업의 상태를 추적하는 모델"""
    job_id: str
    status: str  # PENDING, PROCESSING, COMPLETED, FAILED
    progress: int  # 0 ~ 100
    message: str
    created_at: str
    completed_at: Optional[str] = None
    result: Optional[dict] = None
    error: Optional[str] = None


class Point3DResponse(BaseModel):
    """3D 좌표 응답 모델"""
    x: float
    y: float
    z: float


class PathSegmentResponse(BaseModel):
    """경로 세그먼트 응답 모델"""
    sequence_order: int
    start_point: Point3DResponse
    end_point: Point3DResponse
    length: float


class FloorPathResponse(BaseModel):
    """층별 경로 응답 모델"""
    floor_level: int
    floor_name: str
    segments: List[PathSegmentResponse]
    bounds: dict
    total_distance: float


class VerticalPassageResponse(BaseModel):
    """수직 통로(계단/엘리베이터) 응답 모델"""
    type: str  # STAIRCASE 또는 ELEVATOR
    from_floor_level: int
    to_floor_level: int
    segments: List[PathSegmentResponse]
    entry_point: Point3DResponse
    exit_point: Point3DResponse


class PathNodeResponse(BaseModel):
    """경로 노드 응답 모델 (길찾기 그래프용)"""
    id: str
    x: float
    y: float
    z: float
    type: str  # WAYPOINT, JUNCTION, ENDPOINT, POI_CANDIDATE
    floor_level: Optional[int] = None


class PathEdgeResponse(BaseModel):
    """경로 엣지 응답 모델 (길찾기 그래프용)"""
    id: str
    from_node_id: str
    to_node_id: str
    distance: float
    edge_type: str  # HORIZONTAL, VERTICAL_STAIRCASE, VERTICAL_ELEVATOR
    is_bidirectional: bool = True


class ProcessingResultResponse(BaseModel):
    """전체 처리 결과 응답 모델"""
    job_id: str
    total_nodes: int
    total_distance: float
    floor_paths: List[FloorPathResponse]
    vertical_passages: List[VerticalPassageResponse]
    path_nodes: List[PathNodeResponse]  # 추가: 그래프 노드
    path_edges: List[PathEdgeResponse]  # 추가: 그래프 엣지
    preview_image_path: str
    processed_preview_path: str
    stats: dict


# =============================================================================
# 헬스 체크
# =============================================================================

@app.get("/health", tags=["System"], summary="서비스 상태 확인")
async def health_check():
    """
    서비스 상태 확인

    Returns:
        서비스 상태 및 타임스탬프
    """
    return {
        "status": "healthy",
        "service": "Indoor Path Processing Service",
        "timestamp": datetime.now().isoformat()
    }


# =============================================================================
# 파일 업로드
# =============================================================================

@app.post("/api/v1/upload", tags=["Upload"], summary="RTAB-Map DB 파일 업로드", status_code=201,
           responses={400: {"description": "잘못된 파일 형식 (.db 파일만 허용)"}})
async def upload_db_file(file: UploadFile = File(...)):
    """
    RTAB-Map .db 파일 업로드

    Args:
        file: 업로드할 .db 파일

    Returns:
        - file_id: 파일 고유 ID (이후 처리에 사용)
        - filename: 원본 파일명
        - file_path: 저장된 경로
        - size: 파일 크기 (bytes)

    Raises:
        400: .db 파일이 아닌 경우
        500: 저장 실패
    """
    # 파일 확장자 검증
    if not file.filename.endswith('.db'):
        raise HTTPException(
            status_code=400,
            detail="파일 형식 오류: .db 파일만 업로드할 수 있습니다."
        )

    # 고유 파일 ID 생성
    file_id = str(uuid.uuid4())
    file_path = os.path.join(UPLOAD_DIR, f"{file_id}.db")

    try:
        # 파일 저장
        contents = await file.read()
        with open(file_path, "wb") as f:
            f.write(contents)

        return {
            "file_id": file_id,
            "filename": file.filename,
            "file_path": file_path,
            "size": len(contents),
            "size_mb": round(len(contents) / (1024 * 1024), 2)
        }

    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail=f"파일 저장 실패: {str(e)}"
        )


# =============================================================================
# 처리 시작
# =============================================================================

@app.post("/api/v1/process/{file_id}", tags=["Processing"], summary="경로 처리 작업 시작",
           responses={404: {"description": "파일을 찾을 수 없음"}})
async def start_processing(file_id: str, background_tasks: BackgroundTasks):
    """
    경로 처리 작업 시작 (비동기)

    업로드된 DB 파일에 대해 백그라운드에서 처리를 시작합니다.
    처리 상태는 /api/v1/jobs/{job_id}에서 확인할 수 있습니다.

    Args:
        file_id: 업로드 시 받은 파일 ID

    Returns:
        - job_id: 작업 ID
        - status: 현재 상태 (PENDING)

    Raises:
        404: 파일을 찾을 수 없음
    """
    file_path = os.path.join(UPLOAD_DIR, f"{file_id}.db")

    if not os.path.exists(file_path):
        raise HTTPException(
            status_code=404,
            detail=f"파일을 찾을 수 없습니다: {file_id}"
        )

    # 작업 생성
    job_id = str(uuid.uuid4())
    processing_jobs[job_id] = ProcessingJob(
        job_id=job_id,
        status="PENDING",
        progress=0,
        message="처리 작업이 대기열에 추가되었습니다.",
        created_at=datetime.now().isoformat()
    )

    # 백그라운드에서 처리 시작
    background_tasks.add_task(process_path_async, job_id, file_path)

    return {"job_id": job_id, "status": "PENDING"}


# =============================================================================
# 비동기 처리 로직
# =============================================================================

async def process_path_async(job_id: str, file_path: str):
    """
    경로 처리 메인 파이프라인 (백그라운드 태스크)

    [처리 단계]
    1. 궤적 추출 (10%)
    2. 수직 통로 감지 (25%)
    3. 층 분리 (35%)
    4. 중복 제거 (50%)
    5. RDP + 직선 스냅 (65%)
    6. 그래프 구축 (85%)
    7. 결과 생성 (100%)

    Args:
        job_id: 작업 ID
        file_path: DB 파일 경로
    """
    try:
        job = processing_jobs[job_id]
        job.status = "PROCESSING"

        # ─────────────────────────────────────────────────────────────────
        # Step 1: 궤적 추출 (10%)
        # ─────────────────────────────────────────────────────────────────
        job.progress = 5
        job.message = "DB에서 궤적 추출 중..."

        raw_positions, node_ids = await asyncio.to_thread(
            extract_trajectory_from_db, file_path
        )

        # 기본 통계
        trajectory_stats = get_trajectory_stats(raw_positions)
        job.progress = 10
        job.message = f"궤적 추출 완료: {len(raw_positions)}개 포인트"

        # ─────────────────────────────────────────────────────────────────
        # Step 2: 수직 통로 감지 (25%)
        # ─────────────────────────────────────────────────────────────────
        job.progress = 15
        job.message = "계단/엘리베이터 감지 중..."

        vertical_passages, stair_mask = await asyncio.to_thread(
            detect_stairs_first, raw_positions
        )

        job.progress = 25
        passage_count = len(vertical_passages)
        job.message = f"수직 통로 {passage_count}개 감지"

        # ─────────────────────────────────────────────────────────────────
        # Step 3: 층 분리 (35%)
        # ─────────────────────────────────────────────────────────────────
        job.progress = 30
        job.message = "층 분리 중..."

        floors_data = await asyncio.to_thread(
            separate_floors, raw_positions, node_ids, stair_mask=stair_mask
        )

        # 수직 통로에 층 정보 할당
        vertical_passages = assign_floors_to_stairs(vertical_passages, floors_data)

        job.progress = 35
        floor_count = len(floors_data)
        job.message = f"{floor_count}개 층 분리 완료"

        # ─────────────────────────────────────────────────────────────────
        # Step 4: 중복 제거 (50%)
        # ─────────────────────────────────────────────────────────────────
        job.progress = 40
        job.message = "중복 경로 제거 중..."

        deduplicated_floors = {}
        for floor_level, floor_data in floors_data.items():
            # 이상치 제거
            cleaned = await asyncio.to_thread(remove_outliers, floor_data['positions'])
            # 왕복 구간 병합
            merged = await asyncio.to_thread(
                merge_overlapping_segments, cleaned, overlap_threshold=1.0
            )
            # 공간적 중복 제거
            deduplicated = await asyncio.to_thread(
                deduplicate_path, merged, distance_threshold=0.5
            )
            deduplicated_floors[floor_level] = deduplicated

        job.progress = 50
        job.message = "중복 제거 완료"

        # ─────────────────────────────────────────────────────────────────
        # Step 5: RDP + 직선 스냅 (65%)
        # ─────────────────────────────────────────────────────────────────
        # RDP로 핵심 꼭짓점을 추출하고, 꼭짓점 사이를 직선으로 연결합니다.
        job.progress = 55
        job.message = "경로 직선화 중 (RDP + 직선 스냅)..."

        smoothed_floors = {}
        for floor_level, positions in deduplicated_floors.items():
            snapped = await asyncio.to_thread(
                snap_to_lines, positions, epsilon=0.5, point_spacing=0.5
            )
            smoothed_floors[floor_level] = snapped

        job.progress = 65
        job.message = "직선화 완료"

        # ─────────────────────────────────────────────────────────────────
        # Step 7: 그래프 구축 (85%)
        # ─────────────────────────────────────────────────────────────────
        job.progress = 75
        job.message = "경로 그래프 구축 중..."

        floor_graphs = {}
        for floor_level, positions in smoothed_floors.items():
            nodes, edges = await asyncio.to_thread(
                build_path_graph, positions, floor_level
            )
            floor_graphs[floor_level] = (nodes, edges)

        # 층 간 그래프 병합
        all_nodes, all_edges = merge_floor_graphs(floor_graphs, vertical_passages)

        graph_stats = get_graph_stats(all_nodes, all_edges)

        job.progress = 85
        job.message = f"그래프 구축 완료: 노드 {len(all_nodes)}개, 엣지 {len(all_edges)}개"

        # ─────────────────────────────────────────────────────────────────
        # Step 8: 미리보기 이미지 생성 (90%)
        # ─────────────────────────────────────────────────────────────────
        job.progress = 88
        job.message = "미리보기 이미지 생성 중..."

        output_prefix = os.path.join(OUTPUT_DIR, job_id)
        preview_paths = await asyncio.to_thread(
            _generate_preview_images,
            raw_positions,
            smoothed_floors,
            vertical_passages,
            output_prefix
        )

        job.progress = 90
        job.message = "미리보기 생성 완료"

        # ─────────────────────────────────────────────────────────────────
        # Step 9: 결과 생성 (100%)
        # ─────────────────────────────────────────────────────────────────
        job.progress = 95
        job.message = "결과 데이터 생성 중..."

        result = _build_processing_result(
            job_id=job_id,
            raw_positions=raw_positions,
            smoothed_floors=smoothed_floors,
            vertical_passages=vertical_passages,
            all_nodes=all_nodes,
            all_edges=all_edges,
            preview_paths=preview_paths,
            trajectory_stats=trajectory_stats,
            graph_stats=graph_stats
        )

        # 완료
        job.status = "COMPLETED"
        job.progress = 100
        job.message = "처리 완료!"
        job.completed_at = datetime.now().isoformat()
        job.result = result

    except Exception as e:
        # 에러 처리
        job = processing_jobs[job_id]
        job.status = "FAILED"
        job.error = str(e)
        job.message = f"처리 실패: {str(e)}"


def _build_processing_result(
    job_id: str,
    raw_positions: np.ndarray,
    smoothed_floors: dict,
    vertical_passages: list,
    all_nodes: list,
    all_edges: list,
    preview_paths: dict,
    trajectory_stats: dict,
    graph_stats: dict
) -> dict:
    """
    처리 결과 응답 데이터를 생성합니다.

    Args:
        job_id: 작업 ID
        raw_positions: 원본 좌표
        smoothed_floors: 층별 스무딩된 좌표
        vertical_passages: 수직 통로 리스트
        all_nodes: 전체 노드 리스트
        all_edges: 전체 엣지 리스트
        preview_paths: 미리보기 이미지 경로
        trajectory_stats: 궤적 통계
        graph_stats: 그래프 통계

    Returns:
        결과 딕셔너리
    """
    floor_paths = []
    total_distance = 0

    # 층별 경로 데이터 생성
    for floor_level, positions in smoothed_floors.items():
        positions = np.array(positions)
        segments = []

        for i in range(len(positions) - 1):
            start = positions[i]
            end = positions[i + 1]
            length = float(np.linalg.norm(end - start))
            total_distance += length

            segments.append({
                "sequence_order": i,
                "start_point": {
                    "x": float(start[0]),
                    "y": float(start[1]),
                    "z": float(start[2])
                },
                "end_point": {
                    "x": float(end[0]),
                    "y": float(end[1]),
                    "z": float(end[2])
                },
                "length": length
            })

        # 경계 계산
        bounds = {
            "min_x": float(positions[:, 0].min()),
            "max_x": float(positions[:, 0].max()),
            "min_y": float(positions[:, 1].min()),
            "max_y": float(positions[:, 1].max())
        }

        floor_distance = sum(s["length"] for s in segments)

        # 층 이름 생성 (음수면 지하)
        if floor_level >= 0:
            floor_name = f"{floor_level}층" if floor_level > 0 else "1층"
        else:
            floor_name = f"B{abs(floor_level)}"

        floor_paths.append({
            "floor_level": floor_level,
            "floor_name": floor_name,
            "segments": segments,
            "bounds": bounds,
            "total_distance": floor_distance
        })

    # 수직 통로 데이터 생성
    passage_results = []
    for passage in vertical_passages:
        positions = np.array(passage['positions'])
        segments = []

        for i in range(len(positions) - 1):
            start = positions[i]
            end = positions[i + 1]
            length = float(np.linalg.norm(end - start))

            segments.append({
                "sequence_order": i,
                "start_point": {"x": float(start[0]), "y": float(start[1]), "z": float(start[2])},
                "end_point": {"x": float(end[0]), "y": float(end[1]), "z": float(end[2])},
                "length": length
            })

        passage_results.append({
            "type": passage['type'],
            "from_floor_level": passage.get('from_floor', 0),
            "to_floor_level": passage.get('to_floor', 0),
            "segments": segments,
            "entry_point": {
                "x": float(positions[0][0]),
                "y": float(positions[0][1]),
                "z": float(positions[0][2])
            },
            "exit_point": {
                "x": float(positions[-1][0]),
                "y": float(positions[-1][1]),
                "z": float(positions[-1][2])
            }
        })

    return {
        "job_id": job_id,
        "total_nodes": len(raw_positions),
        "total_distance": total_distance,
        "floor_paths": floor_paths,
        "vertical_passages": passage_results,
        "path_nodes": all_nodes,
        "path_edges": all_edges,
        "preview_image_path": preview_paths.get('raw', ''),
        "processed_preview_path": preview_paths.get('processed', ''),
        "stats": {
            **trajectory_stats,
            **graph_stats
        }
    }


def _generate_preview_images(
    raw_positions: np.ndarray,
    smoothed_floors: dict,
    vertical_passages: list,
    output_prefix: str
) -> dict:
    """
    미리보기 이미지를 생성합니다.

    시각화 모듈이 없는 경우 빈 경로를 반환합니다.

    Args:
        raw_positions: 원본 좌표
        smoothed_floors: 층별 처리된 좌표
        vertical_passages: 수직 통로 리스트
        output_prefix: 출력 파일 접두사

    Returns:
        이미지 경로 딕셔너리
    """
    try:
        from services.visualization import generate_preview_images
        return generate_preview_images(
            raw_positions,
            smoothed_floors,
            vertical_passages,
            output_prefix
        )
    except ImportError:
        # 시각화 모듈 없으면 빈 경로 반환
        return {'raw': '', 'processed': ''}


# =============================================================================
# 작업 상태 조회
# =============================================================================

@app.get("/api/v1/jobs/{job_id}", tags=["Processing"], summary="처리 작업 상태 조회",
         responses={404: {"description": "작업을 찾을 수 없음"}})
async def get_job_status(job_id: str):
    """
    처리 작업 상태 조회

    Args:
        job_id: 작업 ID

    Returns:
        - job_id: 작업 ID
        - status: 상태 (PENDING/PROCESSING/COMPLETED/FAILED)
        - progress: 진행률 (0-100)
        - message: 현재 단계 메시지
        - created_at: 생성 시각
        - completed_at: 완료 시각 (완료 시)
        - error: 에러 메시지 (실패 시)

    Raises:
        404: 작업을 찾을 수 없음
    """
    if job_id not in processing_jobs:
        raise HTTPException(
            status_code=404,
            detail=f"작업을 찾을 수 없습니다: {job_id}"
        )

    job = processing_jobs[job_id]

    # result는 별도 엔드포인트에서 제공
    response = job.model_dump()
    if response.get('result'):
        del response['result']

    return response


@app.get("/api/v1/jobs/{job_id}/result", tags=["Processing"], summary="처리 작업 결과 조회",
         response_model=ProcessingResultResponse,
         responses={400: {"description": "작업이 아직 완료되지 않음"}, 404: {"description": "작업을 찾을 수 없음"}})
async def get_job_result(job_id: str):
    """
    처리 작업 결과 조회

    Args:
        job_id: 작업 ID

    Returns:
        ProcessingResultResponse 형식의 처리 결과

    Raises:
        404: 작업을 찾을 수 없음
        400: 작업이 아직 완료되지 않음
    """
    if job_id not in processing_jobs:
        raise HTTPException(
            status_code=404,
            detail=f"작업을 찾을 수 없습니다: {job_id}"
        )

    job = processing_jobs[job_id]

    if job.status != "COMPLETED":
        raise HTTPException(
            status_code=400,
            detail=f"작업이 아직 완료되지 않았습니다. 현재 상태: {job.status}"
        )

    return job.result


# =============================================================================
# 미리보기 이미지
# =============================================================================

@app.get("/api/v1/preview/{job_id}/{image_type}", tags=["Preview"], summary="미리보기 이미지 조회",
         responses={400: {"description": "잘못된 이미지 타입"}, 404: {"description": "이미지를 찾을 수 없음"}})
async def get_preview_image(job_id: str, image_type: str):
    """
    미리보기 이미지 조회

    Args:
        job_id: 작업 ID
        image_type: 이미지 타입 ('raw' 또는 'processed')

    Returns:
        PNG 이미지 파일

    Raises:
        400: 잘못된 이미지 타입
        404: 이미지를 찾을 수 없음
    """
    if image_type not in ['raw', 'processed']:
        raise HTTPException(
            status_code=400,
            detail="이미지 타입은 'raw' 또는 'processed'만 가능합니다."
        )

    image_path = os.path.join(OUTPUT_DIR, f"{job_id}_{image_type}.png")

    if not os.path.exists(image_path):
        raise HTTPException(
            status_code=404,
            detail="이미지를 찾을 수 없습니다."
        )

    return FileResponse(image_path, media_type="image/png")


# =============================================================================
# 메인 실행
# =============================================================================

if __name__ == "__main__":
    import uvicorn

    print("=" * 60)
    print("  실내 경로 처리 서비스 (Indoor Path Processing Service)")
    print("=" * 60)
    print(f"  Upload Directory: {UPLOAD_DIR}")
    print(f"  Output Directory: {OUTPUT_DIR}")
    print("-" * 60)
    print("  Swagger UI: http://localhost:8000/docs")
    print("  ReDoc:      http://localhost:8000/redoc")
    print("=" * 60)

    uvicorn.run(app, host="0.0.0.0", port=8000)
