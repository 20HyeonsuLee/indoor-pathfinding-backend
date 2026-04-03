"""
PLY 추출 모듈

RTAB-Map DB에서 시각화용 경량 PLY 파일을 추출한다.
rtabmap-export CLI를 사용하여 밀집 포인트클라우드를 생성하고,
xyz + rgb만 남겨 파일 크기를 최소화한다.
"""
import subprocess
import tempfile
import os
import numpy as np

VISUALIZATION_VOXEL_SIZE = 0.05

PLY_TYPE_MAP = {
    'float': ('<f4', 4),
    'float32': ('<f4', 4),
    'double': ('<f8', 8),
    'float64': ('<f8', 8),
    'uchar': ('u1', 1),
    'uint8': ('u1', 1),
    'char': ('i1', 1),
    'int8': ('i1', 1),
    'ushort': ('<u2', 2),
    'uint16': ('<u2', 2),
    'short': ('<i2', 2),
    'int16': ('<i2', 2),
    'uint': ('<u4', 4),
    'uint32': ('<u4', 4),
    'int': ('<i4', 4),
    'int32': ('<i4', 4),
}

COLOR_NAMES = {
    'red': 'r', 'green': 'g', 'blue': 'b',
    'r': 'r', 'g': 'g', 'b': 'b',
    'diffuse_red': 'r', 'diffuse_green': 'g', 'diffuse_blue': 'b',
}


def extract_visualization_ply(db_path, output_path, voxel_size=VISUALIZATION_VOXEL_SIZE):
    """웹 시각화용 경량 PLY 파일을 추출한다."""
    with tempfile.TemporaryDirectory() as tmpdir:
        cmd = [
            'rtabmap-export',
            '--cloud',
            '--voxel', str(voxel_size),
            '--max_range', '4',
            '--depth_confidence', '20',
            '--decimation', '2',
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


def _parse_ply_header(f):
    """PLY 헤더를 파싱하여 vertex 프로퍼티 목록과 정점 수를 반환한다."""
    n_vertices = 0
    properties = []
    in_vertex_element = False

    while True:
        line = f.readline().decode('ascii', errors='ignore').strip()
        if line.startswith('element vertex'):
            n_vertices = int(line.split()[-1])
            in_vertex_element = True
        elif line.startswith('element '):
            in_vertex_element = False
        elif in_vertex_element and line.startswith('property') and not line.startswith('property list'):
            parts = line.split()
            ply_type = parts[1]
            name = parts[2]
            if ply_type in PLY_TYPE_MAP:
                np_type, size = PLY_TYPE_MAP[ply_type]
                properties.append((name, np_type, size))
            else:
                raise ValueError(f"알 수 없는 PLY 타입: {ply_type}")
        elif line == 'end_header':
            break

    return n_vertices, properties


def _convert_ply_xyz_rgb(source_ply, output_path):
    """PLY 헤더를 동적 파싱하여 xyz+rgb만 추출한다."""
    with open(source_ply, 'rb') as f:
        n_vertices, properties = _parse_ply_header(f)

        if n_vertices == 0:
            raise ValueError("포인트클라우드에 정점이 없습니다.")

        prop_names = [p[0] for p in properties]
        print(f"  PLY 프로퍼티: {prop_names} ({sum(p[2] for p in properties)} bytes/point)")

        source_dtype = np.dtype([(p[0], p[1]) for p in properties])
        read_size = n_vertices * source_dtype.itemsize
        raw_bytes = f.read(read_size)
        data = np.frombuffer(raw_bytes, dtype=source_dtype, count=n_vertices)

    # rgb 필드명 매핑
    r_field = g_field = b_field = None
    for name, _, _ in properties:
        mapped = COLOR_NAMES.get(name.lower())
        if mapped == 'r': r_field = name
        elif mapped == 'g': g_field = name
        elif mapped == 'b': b_field = name

    has_color = r_field and g_field and b_field
    if has_color:
        print(f"  색상 필드: r={r_field}, g={g_field}, b={b_field}")
    else:
        print(f"  색상 필드 없음 — 흰색으로 대체")

    output_dtype = np.dtype([
        ('x', '<f4'), ('y', '<f4'), ('z', '<f4'),
        ('r', 'u1'), ('g', 'u1'), ('b', 'u1'),
    ])
    output_data = np.empty(n_vertices, dtype=output_dtype)
    output_data['x'] = data['x']
    output_data['y'] = data['y']
    output_data['z'] = data['z']

    if has_color:
        output_data['r'] = data[r_field].astype('u1')
        output_data['g'] = data[g_field].astype('u1')
        output_data['b'] = data[b_field].astype('u1')
    else:
        output_data['r'] = 200
        output_data['g'] = 200
        output_data['b'] = 200

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

    with open(output_path, 'wb') as f:
        f.write(header.encode('ascii'))
        f.write(output_data.tobytes())

    src_size = n_vertices * source_dtype.itemsize
    dst_size = n_vertices * output_dtype.itemsize
    print(f"  PLY 변환: {n_vertices:,}개 포인트, "
          f"{src_size / 1024 / 1024:.1f}MB → {dst_size / 1024 / 1024:.1f}MB")
