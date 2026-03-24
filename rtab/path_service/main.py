"""
실내 경로 처리 서비스 (Indoor Path Processing Service)

RTAB-Map DB 파일을 처리하여 층 분리 및 PLY 추출을 수행합니다.

[처리 파이프라인]
1. DB에서 궤적 추출 (extraction)
2. 수직통로 감지 & 층 분리 (vertical_detector)
3. 층별 경로 데이터 생성
4. PLY 추출 (ply_extraction)

[API 엔드포인트]
- POST /api/v1/upload                    : DB 파일 업로드
- POST /api/v1/process/{file_id}         : 처리 시작 (비동기)
- GET  /api/v1/jobs/{job_id}             : 작업 상태 조회
- GET  /api/v1/jobs/{job_id}/result      : 처리 결과 조회
- POST /api/v1/pointcloud/extract        : 전체 PLY 추출
- POST /api/v1/pointcloud/extract-floor  : 층별 PLY 추출
- GET  /api/v1/pointcloud/{cache_key}/ply: PLY 다운로드
"""

from fastapi import FastAPI, UploadFile, File, HTTPException, BackgroundTasks
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from pydantic import BaseModel
from typing import Optional, Dict
import os
import uuid
import asyncio
import struct
from datetime import datetime
import numpy as np

from services.extraction import extract_trajectory_from_db, get_trajectory_stats
from services.vertical_detector import detect_stairs_first, separate_floors, assign_floors_to_stairs
from services.ply_extraction import extract_visualization_ply


app = FastAPI(
    title="실내 경로 처리 서비스",
    description="RTAB-Map DB 파일에서 층 분리 및 PLY 추출을 수행하는 서비스",
    version="2.0.0",
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


class FloorPlyRequest(BaseModel):
    source_cache_key: str
    min_z: float
    max_z: float


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
# 처리 시작
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
# 비동기 처리
# =============================================================================

async def process_path_async(job_id: str, file_path: str):
    try:
        job = processing_jobs[job_id]
        job.status = "PROCESSING"

        # Step 1: 궤적 추출
        job.progress = 10
        job.message = "궤적 추출 중..."

        raw_positions, node_ids = await asyncio.to_thread(
            extract_trajectory_from_db, file_path
        )
        trajectory_stats = get_trajectory_stats(raw_positions)

        job.progress = 30
        job.message = f"궤적 추출 완료: {len(raw_positions)}개 포인트"

        # Step 2: 수직통로 감지
        job.progress = 40
        job.message = "계단/엘리베이터 감지 중..."

        vertical_passages, stair_mask = await asyncio.to_thread(
            detect_stairs_first, raw_positions
        )

        job.progress = 50
        job.message = f"수직 통로 {len(vertical_passages)}개 감지"

        # Step 3: 층 분리
        job.progress = 60
        job.message = "층 분리 중..."

        floors_data = await asyncio.to_thread(
            separate_floors, raw_positions, node_ids,
            stair_mask=stair_mask, vertical_passages=vertical_passages
        )
        vertical_passages = assign_floors_to_stairs(vertical_passages, floors_data)

        job.progress = 80
        job.message = f"{len(floors_data)}개 층 분리 완료"

        # Step 4: 결과 생성
        job.progress = 90
        job.message = "결과 생성 중..."

        result = _build_result(
            job_id, raw_positions, floors_data,
            vertical_passages, trajectory_stats,
        )

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


def _build_result(
    job_id: str,
    raw_positions: np.ndarray,
    floors_data: list,
    vertical_passages: list,
    trajectory_stats: dict,
) -> dict:
    floor_paths = []
    total_distance = 0.0

    for floor_info in floors_data:
        floor_level = floor_info['floor_level']
        positions = np.array(floor_info['positions'])

        if len(positions) < 2:
            continue

        segments = []
        floor_distance = 0.0

        for i in range(len(positions) - 1):
            start = positions[i]
            end = positions[i + 1]
            length = float(np.linalg.norm(end - start))
            floor_distance += length

            segments.append({
                "sequence_order": i,
                "start_point": {"x": float(start[0]), "y": float(start[1]), "z": float(start[2])},
                "end_point": {"x": float(end[0]), "y": float(end[1]), "z": float(end[2])},
                "length": length,
            })

        total_distance += floor_distance
        floor_name = f"{floor_level}층" if floor_level > 0 else f"B{abs(floor_level)}"

        floor_paths.append({
            "floor_level": floor_level,
            "floor_name": floor_name,
            "segments": segments,
            "bounds": {
                "min_x": float(positions[:, 0].min()),
                "max_x": float(positions[:, 0].max()),
                "min_y": float(positions[:, 1].min()),
                "max_y": float(positions[:, 1].max()),
            },
            "total_distance": floor_distance,
        })

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
                "length": length,
            })

        passage_results.append({
            "type": passage['type'],
            "from_floor_level": passage.get('from_floor', 0),
            "to_floor_level": passage.get('to_floor', 0),
            "segments": segments,
            "entry_point": {"x": float(positions[0][0]), "y": float(positions[0][1]), "z": float(positions[0][2])},
            "exit_point": {"x": float(positions[-1][0]), "y": float(positions[-1][1]), "z": float(positions[-1][2])},
        })

    return {
        "job_id": job_id,
        "total_nodes": len(raw_positions),
        "total_distance": total_distance,
        "floor_paths": floor_paths,
        "vertical_passages": passage_results,
        "path_nodes": [],
        "path_edges": [],
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
# 포인트클라우드 PLY
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


@app.post("/api/v1/pointcloud/extract-floor")
async def extract_floor_ply(request: FloorPlyRequest):
    import hashlib

    source_path = os.path.join(PLY_CACHE_DIR, f"{request.source_cache_key}.ply")
    if not os.path.exists(source_path):
        raise HTTPException(status_code=404, detail="원본 PLY 없음. /extract 먼저 호출 필요")

    floor_key = hashlib.md5(
        f"{request.source_cache_key}_{request.min_z}_{request.max_z}".encode()
    ).hexdigest()
    floor_ply_path = os.path.join(PLY_CACHE_DIR, f"{floor_key}.ply")

    if os.path.exists(floor_ply_path):
        return {
            "cache_key": floor_key,
            "status": "CACHED",
            "size_mb": round(os.path.getsize(floor_ply_path) / 1024 / 1024, 2),
        }

    with open(source_path, 'rb') as f:
        n_vertices = 0
        while True:
            line = f.readline().decode('ascii', errors='ignore').strip()
            if line.startswith('element vertex'):
                n_vertices = int(line.split()[-1])
            if line == 'end_header':
                break
        data = f.read()

    point_size = 15
    filtered = bytearray()
    count = 0

    for i in range(n_vertices):
        offset = i * point_size
        x, y, z = struct.unpack_from('<fff', data, offset)
        if request.min_z <= z <= request.max_z:
            filtered.extend(data[offset:offset + point_size])
            count += 1

    header = (
        "ply\n"
        "format binary_little_endian 1.0\n"
        f"element vertex {count}\n"
        "property float x\n"
        "property float y\n"
        "property float z\n"
        "property uchar red\n"
        "property uchar green\n"
        "property uchar blue\n"
        "end_header\n"
    )

    with open(floor_ply_path, 'wb') as f:
        f.write(header.encode('ascii'))
        f.write(bytes(filtered))

    size_mb = round(os.path.getsize(floor_ply_path) / 1024 / 1024, 2)
    return {"cache_key": floor_key, "status": "CREATED", "point_count": count, "size_mb": size_mb}


# =============================================================================
# 메인 실행
# =============================================================================

if __name__ == "__main__":
    import uvicorn
    print("Indoor Path Processing Service")
    print(f"  Swagger UI: http://localhost:8000/docs")
    uvicorn.run(app, host="0.0.0.0", port=8000)
