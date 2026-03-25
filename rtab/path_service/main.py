"""
실내 경로 처리 서비스 (Indoor Path Processing Service)

RTAB-Map DB 파일을 처리하여 궤적 추출 및 PLY 변환을 수행합니다.
층별로 DB가 업로드되므로 층 분리/수직통로 감지는 불필요합니다.

[처리 파이프라인]
1. DB에서 궤적 추출 (extraction)
2. 경로 데이터 생성 (단일 층)
3. PLY 추출 (ply_extraction)

[API 엔드포인트]
- POST /api/v1/upload                    : DB 파일 업로드
- POST /api/v1/process/{file_id}         : 처리 시작 (비동기)
- GET  /api/v1/jobs/{job_id}             : 작업 상태 조회
- GET  /api/v1/jobs/{job_id}/result      : 처리 결과 조회
- POST /api/v1/pointcloud/extract        : DB → PLY 추출
- GET  /api/v1/pointcloud/{cache_key}/ply: PLY 다운로드
- POST /api/v1/merge                     : 여러 DB 병합 (rtabmap-reprocess)
"""

from fastapi import FastAPI, UploadFile, File, HTTPException, BackgroundTasks
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from pydantic import BaseModel
from typing import Optional, Dict, List
import os
import uuid
import asyncio
import subprocess
from datetime import datetime
import numpy as np

from services.extraction import extract_trajectory_from_db, get_trajectory_stats
from services.ply_extraction import extract_visualization_ply


app = FastAPI(
    title="실내 경로 처리 서비스",
    description="RTAB-Map DB 파일에서 궤적 추출 및 PLY 변환을 수행하는 서비스",
    version="3.0.0",
)

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

UPLOAD_DIR = os.getenv("UPLOAD_DIR", "./uploads")
OUTPUT_DIR = os.getenv("OUTPUT_DIR", "./output")
PLY_CACHE_DIR = os.getenv("PLY_CACHE_DIR", "./ply_cache")

os.makedirs(UPLOAD_DIR, exist_ok=True)
os.makedirs(OUTPUT_DIR, exist_ok=True)
os.makedirs(PLY_CACHE_DIR, exist_ok=True)

processing_jobs: Dict[str, "ProcessingJob"] = {}
merge_jobs: Dict[str, "MergeJob"] = {}


# =============================================================================
# 데이터 모델
# =============================================================================

class ProcessingJob(BaseModel):
    job_id: str
    status: str
    progress: int
    message: str
    created_at: str
    completed_at: Optional[str] = None
    result: Optional[dict] = None
    error: Optional[str] = None


class PlyExtractRequest(BaseModel):
    db_path: Optional[str] = None
    file_id: Optional[str] = None


class MergeRequest(BaseModel):
    chunk_file_paths: List[str]
    output_path: str


class MergeJob(BaseModel):
    job_id: str
    status: str
    message: str
    created_at: str
    completed_at: Optional[str] = None
    output_path: Optional[str] = None
    error: Optional[str] = None
    merge_stats: Optional[dict] = None


# =============================================================================
# 헬스 체크
# =============================================================================

@app.get("/health")
async def health_check():
    return {"status": "healthy", "timestamp": datetime.now().isoformat()}


# =============================================================================
# 파일 업로드
# =============================================================================

@app.post("/api/v1/upload", status_code=201)
async def upload_db_file(file: UploadFile = File(...)):
    if not file.filename.endswith('.db'):
        raise HTTPException(status_code=400, detail=".db 파일만 업로드 가능합니다.")

    file_id = str(uuid.uuid4())
    file_path = os.path.join(UPLOAD_DIR, f"{file_id}.db")

    contents = await file.read()
    with open(file_path, "wb") as f:
        f.write(contents)

    return {
        "file_id": file_id,
        "filename": file.filename,
        "file_path": file_path,
        "size": len(contents),
    }


# =============================================================================
# 처리 시작 (궤적 추출 — 층 분리 없이 단일 층)
# =============================================================================

@app.post("/api/v1/process/{file_id}")
async def start_processing(file_id: str, background_tasks: BackgroundTasks):
    file_path = os.path.join(UPLOAD_DIR, f"{file_id}.db")

    if not os.path.exists(file_path):
        raise HTTPException(status_code=404, detail=f"파일 없음: {file_id}")

    job_id = str(uuid.uuid4())
    processing_jobs[job_id] = ProcessingJob(
        job_id=job_id,
        status="PENDING",
        progress=0,
        message="대기 중",
        created_at=datetime.now().isoformat(),
    )

    background_tasks.add_task(process_path_async, job_id, file_path)
    return {"job_id": job_id, "status": "PENDING"}


# =============================================================================
# 비동기 처리 (단순화: 궤적 추출 → 결과 생성)
# =============================================================================

async def process_path_async(job_id: str, file_path: str):
    try:
        job = processing_jobs[job_id]
        job.status = "PROCESSING"

        # Step 1: 궤적 추출
        job.progress = 20
        job.message = "궤적 추출 중..."

        raw_positions, node_ids = await asyncio.to_thread(
            extract_trajectory_from_db, file_path
        )
        trajectory_stats = get_trajectory_stats(raw_positions)

        job.progress = 60
        job.message = f"궤적 추출 완료: {len(raw_positions)}개 포인트"

        # Step 2: 결과 생성 (단일 층 — 층 분리 없음)
        job.progress = 90
        job.message = "결과 생성 중..."

        result = _build_single_floor_result(job_id, raw_positions, trajectory_stats)

        job.status = "COMPLETED"
        job.progress = 100
        job.message = "처리 완료"
        job.completed_at = datetime.now().isoformat()
        job.result = result

    except Exception as e:
        job = processing_jobs[job_id]
        job.status = "FAILED"
        job.error = str(e)
        job.message = f"처리 실패: {str(e)}"


def _build_single_floor_result(
    job_id: str,
    positions: np.ndarray,
    trajectory_stats: dict,
) -> dict:
    """단일 층 결과를 생성한다. 층 분리 없이 전체 궤적을 하나의 floor_path로."""

    segments = []
    total_distance = 0.0

    for i in range(len(positions) - 1):
        start = positions[i]
        end = positions[i + 1]
        length = float(np.linalg.norm(end - start))
        total_distance += length

        segments.append({
            "sequence_order": i,
            "start_point": {"x": float(start[0]), "y": float(start[1]), "z": float(start[2])},
            "end_point": {"x": float(end[0]), "y": float(end[1]), "z": float(end[2])},
            "length": length,
        })

    floor_path = {
        "floor_level": 1,
        "floor_name": "1층",
        "segments": segments,
        "bounds": {
            "min_x": float(positions[:, 0].min()) if len(positions) > 0 else 0,
            "max_x": float(positions[:, 0].max()) if len(positions) > 0 else 0,
            "min_y": float(positions[:, 1].min()) if len(positions) > 0 else 0,
            "max_y": float(positions[:, 1].max()) if len(positions) > 0 else 0,
        },
        "total_distance": total_distance,
    }

    return {
        "job_id": job_id,
        "total_nodes": len(positions),
        "total_distance": total_distance,
        "floor_paths": [floor_path],
        "vertical_passages": [],
        "preview_image_path": "",
        "processed_preview_path": "",
        "stats": trajectory_stats,
    }


# =============================================================================
# 작업 상태/결과 조회
# =============================================================================

@app.get("/api/v1/jobs/{job_id}")
async def get_job_status(job_id: str):
    if job_id not in processing_jobs:
        raise HTTPException(status_code=404, detail=f"작업 없음: {job_id}")

    response = processing_jobs[job_id].model_dump()
    response.pop('result', None)
    return response


@app.get("/api/v1/jobs/{job_id}/result")
async def get_job_result(job_id: str):
    if job_id not in processing_jobs:
        raise HTTPException(status_code=404, detail=f"작업 없음: {job_id}")

    job = processing_jobs[job_id]
    if job.status != "COMPLETED":
        raise HTTPException(status_code=400, detail=f"미완료 상태: {job.status}")

    return job.result


# =============================================================================
# 포인트클라우드 PLY (DB → PLY 직접 변환)
# =============================================================================

@app.post("/api/v1/pointcloud/extract")
async def extract_pointcloud_ply(request: PlyExtractRequest):
    import hashlib

    if request.file_id:
        db_path = os.path.join(UPLOAD_DIR, f"{request.file_id}.db")
    elif request.db_path:
        db_path = request.db_path
    else:
        raise HTTPException(status_code=400, detail="file_id 또는 db_path 필요")

    if not os.path.exists(db_path):
        raise HTTPException(status_code=404, detail=f"파일 없음: {db_path}")

    cache_key = hashlib.md5(db_path.encode()).hexdigest()
    ply_path = os.path.join(PLY_CACHE_DIR, f"{cache_key}.ply")

    if os.path.exists(ply_path):
        file_size = os.path.getsize(ply_path)
        return {
            "cache_key": cache_key,
            "status": "CACHED",
            "ply_path": ply_path,
            "size_bytes": file_size,
            "size_mb": round(file_size / (1024 * 1024), 2),
        }

    await asyncio.to_thread(extract_visualization_ply, db_path, ply_path)

    file_size = os.path.getsize(ply_path)
    return {
        "cache_key": cache_key,
        "status": "CREATED",
        "ply_path": ply_path,
        "size_bytes": file_size,
        "size_mb": round(file_size / (1024 * 1024), 2),
    }


@app.get("/api/v1/pointcloud/{cache_key}/ply")
async def get_pointcloud_ply(cache_key: str):
    ply_path = os.path.join(PLY_CACHE_DIR, f"{cache_key}.ply")

    if not os.path.exists(ply_path):
        raise HTTPException(status_code=404, detail="PLY 없음. /extract 먼저 호출 필요")

    return FileResponse(
        ply_path,
        media_type="application/octet-stream",
        filename=f"{cache_key}.ply",
        headers={"Content-Encoding": "identity"},
    )


# =============================================================================
# DB 병합 (rtabmap-reprocess)
# =============================================================================

@app.post("/api/v1/merge")
async def merge_databases(request: MergeRequest, background_tasks: BackgroundTasks):
    """여러 .db 파일을 rtabmap-reprocess로 병합한다."""

    # 파일 존재 확인
    for path in request.chunk_file_paths:
        if not os.path.exists(path):
            raise HTTPException(status_code=404, detail=f"파일 없음: {path}")

    if len(request.chunk_file_paths) < 2:
        raise HTTPException(status_code=400, detail="병합하려면 2개 이상의 파일이 필요합니다.")

    job_id = str(uuid.uuid4())
    merge_jobs[job_id] = MergeJob(
        job_id=job_id,
        status="MERGING",
        message="병합 시작",
        created_at=datetime.now().isoformat(),
    )

    background_tasks.add_task(
        merge_async, job_id, request.chunk_file_paths, request.output_path
    )

    return {"job_id": job_id, "status": "MERGING"}


async def merge_async(job_id: str, chunk_paths: List[str], output_path: str):
    """rtabmap-reprocess를 호출하여 DB를 병합한다."""
    try:
        job = merge_jobs[job_id]

        # rtabmap-reprocess: 여러 DB를 하나로 병합
        # 사용법: rtabmap-reprocess --Mem/IncrementalMemory false output.db input1.db input2.db ...
        cmd = [
            'rtabmap-reprocess',
            '--Mem/IncrementalMemory', 'false',
            output_path,
            *chunk_paths,
        ]

        print(f"  병합 실행: {' '.join(cmd)}")

        result = await asyncio.to_thread(
            subprocess.run, cmd,
            capture_output=True, text=True, timeout=1800  # 30분 타임아웃
        )

        if result.returncode != 0:
            error_msg = result.stderr[:1000] if result.stderr else "Unknown error"
            job.status = "FAILED"
            job.error = error_msg
            job.message = f"병합 실패: {error_msg}"
            print(f"  병합 실패: {error_msg}")
            return

        if not os.path.exists(output_path):
            job.status = "FAILED"
            job.error = "출력 파일이 생성되지 않았습니다."
            job.message = "병합 실패: 출력 파일 없음"
            return

        file_size = os.path.getsize(output_path)
        job.status = "COMPLETED"
        job.message = "병합 완료"
        job.completed_at = datetime.now().isoformat()
        job.output_path = output_path
        job.merge_stats = {
            "input_count": len(chunk_paths),
            "output_size_bytes": file_size,
            "output_size_mb": round(file_size / (1024 * 1024), 2),
        }

        print(f"  병합 완료: {output_path} ({file_size / 1024 / 1024:.1f} MB)")

    except subprocess.TimeoutExpired:
        job = merge_jobs[job_id]
        job.status = "FAILED"
        job.error = "병합 시간 초과 (30분)"
        job.message = "병합 실패: 타임아웃"
    except Exception as e:
        job = merge_jobs[job_id]
        job.status = "FAILED"
        job.error = str(e)
        job.message = f"병합 실패: {str(e)}"


@app.get("/api/v1/merge/{job_id}")
async def get_merge_status(job_id: str):
    if job_id not in merge_jobs:
        raise HTTPException(status_code=404, detail=f"병합 작업 없음: {job_id}")
    return merge_jobs[job_id].model_dump()


# =============================================================================
# 메인 실행
# =============================================================================

if __name__ == "__main__":
    import uvicorn
    print("Indoor Path Processing Service v3.0")
    print(f"  Swagger UI: http://localhost:8000/docs")
    uvicorn.run(app, host="0.0.0.0", port=8000)
