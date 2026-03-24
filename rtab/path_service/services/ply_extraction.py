"""
PLY 추출 모듈

RTAB-Map DB에서 시각화용 경량 PLY 파일을 추출한다.
rtabmap-export CLI를 사용하여 밀집 포인트클라우드를 생성하고,
xyz + rgb만 남겨 파일 크기를 최소화한다 (31 → 15 bytes/point).
"""
import subprocess
import tempfile
import os
import numpy as np

VISUALIZATION_VOXEL_SIZE = 0.2


def extract_visualization_ply(db_path, output_path, voxel_size=VISUALIZATION_VOXEL_SIZE):
    """
    웹 시각화용 경량 PLY 파일을 추출한다.

    xyz + rgb만 포함하여 파일 크기를 최소화한다.
    (법선, 곡률 제거 → 31 → 15 bytes/point)
    """
    with tempfile.TemporaryDirectory() as tmpdir:
        cmd = [
            'rtabmap-export',
            '--cloud',
            '--voxel', str(voxel_size),
            '--max_range', '5',
            '--depth_confidence', '50',
            '--decimation', '4',
            '--output_dir', tmpdir,
            '--output', 'cloud',
            db_path,
        ]
        print(f"  rtabmap-export 실행: {' '.join(cmd)}")
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
        print(f"  returncode={result.returncode}")
        if result.stdout: print(f"  stdout: {result.stdout[:500]}")
        if result.stderr: print(f"  stderr: {result.stderr[:500]}")

        if result.returncode != 0:
            raise RuntimeError(f"rtabmap-export 실패 (code={result.returncode}):\n{result.stderr[:500]}")

        tmpfiles = os.listdir(tmpdir)
        print(f"  tmpdir 파일: {tmpfiles}")

        source_ply = None
        for f in tmpfiles:
            if f.endswith('.ply'):
                source_ply = os.path.join(tmpdir, f)
                break

        if source_ply is None:
            raise FileNotFoundError(f"PLY 파일이 생성되지 않았습니다. tmpdir 내용: {tmpfiles}")

        _convert_ply_xyz_rgb(source_ply, output_path)

    file_size = os.path.getsize(output_path)
    print(f"  시각화 PLY 생성: {output_path}")
    print(f"  파일 크기: {file_size / (1024 * 1024):.2f} MB")
    return output_path


def _convert_ply_xyz_rgb(source_ply, output_path):
    """
    rtabmap-export PLY(31 bytes/point)에서 xyz+rgb(15 bytes/point)만 추출하여 저장한다.
    """
    with open(source_ply, 'rb') as f:
        n_vertices = 0
        while True:
            line = f.readline().decode('ascii', errors='ignore').strip()
            if line.startswith('element vertex'):
                n_vertices = int(line.split()[-1])
            if line == 'end_header':
                break

        if n_vertices == 0:
            raise ValueError("포인트클라우드에 정점이 없습니다.")

        source_dtype = np.dtype([
            ('x', '<f4'), ('y', '<f4'), ('z', '<f4'),
            ('nx', '<f4'), ('ny', '<f4'), ('nz', '<f4'),
            ('r', 'u1'), ('g', 'u1'), ('b', 'u1'),
            ('curvature', '<f4'),
        ])
        data = np.frombuffer(f.read(n_vertices * source_dtype.itemsize),
                             dtype=source_dtype, count=n_vertices)

    header = (
        "ply\n"
        "format binary_little_endian 1.0\n"
        f"element vertex {n_vertices}\n"
        "property float x\n"
        "property float y\n"
        "property float z\n"
        "property uchar red\n"
        "property uchar green\n"
        "property uchar blue\n"
        "end_header\n"
    )

    output_dtype = np.dtype([
        ('x', '<f4'), ('y', '<f4'), ('z', '<f4'),
        ('r', 'u1'), ('g', 'u1'), ('b', 'u1'),
    ])
    output_data = np.empty(n_vertices, dtype=output_dtype)
    output_data['x'] = data['x']
    output_data['y'] = data['y']
    output_data['z'] = data['z']
    output_data['r'] = data['r']
    output_data['g'] = data['g']
    output_data['b'] = data['b']

    with open(output_path, 'wb') as f:
        f.write(header.encode('ascii'))
        f.write(output_data.tobytes())

    print(f"  PLY 변환: {n_vertices:,}개 포인트, "
          f"{n_vertices * 31 / 1024 / 1024:.1f}MB → {n_vertices * 15 / 1024 / 1024:.1f}MB")
