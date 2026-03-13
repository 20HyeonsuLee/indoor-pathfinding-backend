"""
school.db 경로 처리 파이프라인 + 3D 시각화 스크립트

[파이프라인 순서]
  원본 데이터 (RTAB-Map DB)
    │
    ▼
  1. 궤적 추출         ── DB의 pose BLOB(48byte)을 파싱하여 [N,3] 좌표 배열로 변환
    │
    ▼
  2. 이상치 탐지+보간   ── 연속 포인트 간 거리가 μ+3σ 초과인 점을 양쪽 이웃 중간값으로 교체
    │
    ▼
  3. 가우시안 스무딩     ── XY축에 σ=1.5 가우시안 필터 적용 (Z축은 계단 감지를 위해 원본 유지)
    │
    ▼
  4. 수직통로 감지       ── 슬라이딩 윈도우로 Z축 변화량 분석 → 계단/엘리베이터 구간 분리
     + 층 분리          ── Z값 히스토그램 피크 탐지로 층별 클러스터링 (피크 간 최소 거리 2.1m)
    │
    ▼
  5. 층별 후속 처리
     ├─ 백트래킹 병합   ── 되돌아간 구간 감지하여 제거
     ├─ KD-Tree 중복 제거 ── 0.5m 반경 내 근접 포인트를 하나로 통합 (O(N log N))
     ├─ RDP 단순화      ── 직선 구간의 불필요한 중간점 제거 (ε=0.5m)
     └─ PCA 보간        ── RDP 꼭짓점 사이를 0.5m 간격으로 직선 보간
    │
    ▼
  6. 그래프 구축
     ├─ 교차점 감지     ── 방향 변화 ≥45° 지점을 JUNCTION으로 마킹
     ├─ 노드 추출       ── ENDPOINT + JUNCTION + WAYPOINT(1m 간격)
     ├─ 엣지 연결       ── 인접 노드를 실제 경로 거리로 연결
     └─ 다층 병합       ── 수직통로로 층간 VERTICAL 엣지 추가
"""
import sys
import os
import numpy as np
import matplotlib
matplotlib.use('Agg')  # GUI 없이 이미지 파일로 렌더링
import matplotlib.pyplot as plt
from mpl_toolkits.mplot3d import Axes3D

# 서비스 모듈 경로 등록
sys.path.insert(0, os.path.dirname(__file__))

from services.extraction import extract_trajectory_from_db, get_trajectory_stats
from services.vertical_detector import detect_stairs_first, separate_floors, assign_floors_to_stairs
from services.smoothing import smooth_path, remove_outliers, split_at_gaps
from services.deduplication import deduplicate_path, merge_overlapping_segments
from services.path_flattening import snap_to_lines
from services.junction_detection import build_path_graph, merge_floor_graphs

DB_PATH = os.path.join(os.path.dirname(__file__), '..', 'db', 'school.db')
OUTPUT_DIR = os.path.join(os.path.dirname(__file__), '..', 'exported_images')
os.makedirs(OUTPUT_DIR, exist_ok=True)


def run_pipeline():
    print("=" * 60)
    print("  school.db 파이프라인 실행")
    print("=" * 60)

    # ─────────────────────────────────────────────────────────
    # Step 1: 궤적 추출
    # ─────────────────────────────────────────────────────────
    # RTAB-Map DB의 Node 테이블에서 pose BLOB(48byte = 3x4 변환 행렬)을
    # 파싱하여 카메라 위치 [x, y, z]를 추출한다.
    # 무효 데이터(NaN, Inf, 행렬 전체 0)는 자동 필터링된다.
    print("\n[1/6] 궤적 추출...")
    raw_positions, node_ids = extract_trajectory_from_db(DB_PATH)
    stats = get_trajectory_stats(raw_positions)
    print(f"  포인트: {stats['total_nodes']}개")
    print(f"  총 거리: {stats['total_distance']:.1f}m")
    print(f"  Z 범위: {stats['z_range'][0]:.2f} ~ {stats['z_range'][1]:.2f}m")

    # ─────────────────────────────────────────────────────────
    # Step 2: 이상치 탐지 + 보간
    # ─────────────────────────────────────────────────────────
    # 연속 포인트 간 거리의 통계(μ, σ)를 계산하고,
    # 거리 > μ + 3σ인 포인트를 "이상치"로 판정한다.
    # 이상치는 양쪽 이웃의 중간값으로 선형 보간하여 교체한다.
    # (센서 오류나 트래킹 실패로 인한 순간적 점프를 수정)
    print("\n[2/6] 이상치 탐지 + 보간...")
    cleaned = remove_outliers(raw_positions, threshold_std=3.0)
    outlier_count = np.sum(np.any(cleaned != raw_positions, axis=1))
    print(f"  이상치 {outlier_count}개 보간 완료")

    # ─────────────────────────────────────────────────────────
    # Step 3: 가우시안 스무딩 (XY만, Z 보존)
    # ─────────────────────────────────────────────────────────
    # 각 축에 1D 가우시안 필터(σ=1.5)를 적용하여 손떨림 노이즈를 제거한다.
    # 단, Z축은 원본을 유지한다:
    #   - Z를 스무딩하면 계단의 급격한 높이 변화가 완만해져
    #     다음 단계의 수직통로 감지 정확도가 떨어지기 때문.
    #   - XY만 스무딩해도 후속 중복 제거/RDP의 품질이 크게 향상된다.
    print("\n[3/6] 가우시안 스무딩 (Z 보존)...")
    smoothed = smooth_path(cleaned, sigma=1.5)
    smoothed[:, 2] = cleaned[:, 2]  # Z축은 원본 유지
    print(f"  XY 잔떨림 제거 완료 (sigma=1.5)")

    # ─────────────────────────────────────────────────────────
    # Step 4: 수직통로 감지 + 층 분리
    # ─────────────────────────────────────────────────────────
    # [수직통로 감지]
    # 슬라이딩 윈도우(10점)를 경로를 따라 이동하며 Z축 변화량을 측정한다.
    # 윈도우 내 Z 변화가 임계값(1.5m×0.5) 이상이고 방향이 일관적이면
    # 해당 구간을 수직 이동(계단/엘리베이터)으로 판정한다.
    # XY/Z 이동 비율 < 1.0이면 엘리베이터, ≥ 1.0이면 계단으로 분류.
    print("\n[4/6] 수직통로 감지...")
    vertical_passages, stair_mask = detect_stairs_first(smoothed)
    print(f"  수직통로: {len(vertical_passages)}개")
    for p in vertical_passages:
        print(f"    {p['type']}: {p['z_start']:.1f}m → {p['z_end']:.1f}m ({p['direction']})")

    # [층 분리]
    # 수직통로 포인트를 제외한 나머지의 Z값으로 히스토그램을 생성한다.
    # 히스토그램을 가우시안(σ=1.5)으로 스무딩한 뒤 피크를 탐지한다.
    # 피크 = 각 층의 높이. 피크 간 최소 거리는 층고(3.0m)×0.7 = 2.1m.
    # 각 포인트를 가장 가까운 피크에 할당하여 층별로 분리한다.
    print("      층 분리...")
    floors_data = separate_floors(smoothed, node_ids, stair_mask=stair_mask)
    vertical_passages = assign_floors_to_stairs(vertical_passages, floors_data)

    # 층 분리 실패 시 폴백: 전체를 단일 층으로 처리
    if not floors_data:
        flat = smoothed[~stair_mask] if stair_mask.any() else smoothed
        floors_data = {1: {
            'positions': flat,
            'node_ids': node_ids,
            'z_mean': float(np.mean(flat[:, 2])),
            'point_count': len(flat)
        }}

    for fl, data in floors_data.items():
        print(f"  {fl}층: {data['point_count']}개 포인트 (Z평균={data['z_mean']:.2f}m)")

    # ─────────────────────────────────────────────────────────
    # Step 5: 층별 후속 처리 (중복 제거 → RDP → PCA 보간)
    # ─────────────────────────────────────────────────────────
    # 각 층에 대해 독립적으로 3단계 처리를 수행한다:
    #
    # [5-1] 백트래킹 병합 (merge_overlapping_segments)
    #   - 보행자가 같은 경로를 되돌아간 구간을 감지하여 제거한다.
    #   - 현재 위치가 이전 경로의 1.0m 이내이면 "재방문"으로 판정.
    #   - 재방문 지점에서 새 방향으로 분기하는 곳까지를 건너뛴다.
    #
    # [5-2] KD-Tree 중복 제거 (deduplicate_path)
    #   - 공간적으로 0.5m 이내에 있는 근접 포인트들을 하나로 통합한다.
    #   - KD-Tree(scipy.spatial.cKDTree)로 반경 탐색하여 O(N log N)에 처리.
    #
    # [5-3] RDP + PCA 보간 (snap_to_lines)
    #   - RDP(Ramer-Douglas-Peucker)로 직선 구간의 불필요한 중간점을 제거한다.
    #     (직선에서 ε=0.5m 이내의 점은 생략)
    #   - RDP 꼭짓점 사이를 0.5m 간격으로 직선 보간하여 균일한 포인트를 채운다.
    print("\n[5/6] 층별 후속 처리...")

    # 5-1, 5-2: 백트래킹 병합 + KD-Tree 중복 제거
    deduplicated_floors = {}
    for fl, data in floors_data.items():
        positions = data['positions']
        if len(positions) < 3:
            deduplicated_floors[fl] = positions
            continue
        merged = merge_overlapping_segments(positions, overlap_threshold=1.0)
        deduped = deduplicate_path(merged, distance_threshold=0.5)
        deduplicated_floors[fl] = deduped
        print(f"  {fl}층 중복제거: {len(positions)} → {len(deduped)}개 "
              f"({100*(1-len(deduped)/len(positions)):.0f}% 감소)")

    # 5-3: RDP 단순화 + PCA 보간
    flattened_floors = {}
    for fl, positions in deduplicated_floors.items():
        if len(positions) < 3:
            flattened_floors[fl] = positions
            continue
        snapped = snap_to_lines(positions, epsilon=0.5, point_spacing=0.5)
        flattened_floors[fl] = snapped
        print(f"  {fl}층 RDP+보간: {len(positions)} → {len(snapped)}개")

    # ─────────────────────────────────────────────────────────
    # Step 6: 그래프 구축
    # ─────────────────────────────────────────────────────────
    # 각 층의 좌표 배열을 노드/엣지 그래프로 변환한다:
    #   - 교차점 감지: 연속 방향 벡터의 각도 변화 ≥ 45°인 지점을 JUNCTION으로 마킹
    #   - 노드 추출: 경로 시작/끝 = ENDPOINT, 교차점 = JUNCTION,
    #                나머지는 1.0m 간격으로 WAYPOINT 배치
    #   - 엣지 연결: 인접 노드를 실제 경로 거리(직선 거리 아님)로 연결
    # 층별 그래프를 합친 뒤, 수직통로 입구/출구 노드를 VERTICAL 엣지로 연결한다.
    print("\n[6/6] 그래프 구축...")
    floor_graphs = {}
    for fl, positions in flattened_floors.items():
        if len(positions) < 2:
            continue
        nodes, edges = build_path_graph(positions, floor_level=fl)
        floor_graphs[fl] = (nodes, edges)
        print(f"  {fl}층: 노드 {len(nodes)}개, 엣지 {len(edges)}개")

    # 다층 그래프 병합: 각 층 그래프 + 수직통로 엣지
    all_nodes, all_edges = merge_floor_graphs(floor_graphs, vertical_passages)
    print(f"\n  전체 그래프: 노드 {len(all_nodes)}개, 엣지 {len(all_edges)}개")

    return {
        'raw_positions': raw_positions,
        'smoothed': smoothed,
        'stair_mask': stair_mask,
        'vertical_passages': vertical_passages,
        'floors_data': floors_data,
        'deduplicated_floors': deduplicated_floors,
        'flattened_floors': flattened_floors,
        'all_nodes': all_nodes,
        'all_edges': all_edges,
    }


# =============================================================================
# 3D 시각화
# =============================================================================

def visualize_3d(result):
    """파이프라인 결과를 4장의 3D 이미지로 시각화한다."""

    raw = result['raw_positions']
    stair_mask = result['stair_mask']
    passages = result['vertical_passages']
    floors = result['flattened_floors']
    nodes = result['all_nodes']
    edges = result['all_edges']

    # 층별 색상 팔레트
    floor_colors = {1: '#2196F3', 2: '#4CAF50', 3: '#FF9800', 4: '#E91E63', 5: '#9C27B0'}
    default_color = '#607D8B'

    # =====================================================
    # 이미지 1: 원본 궤적 3D
    # =====================================================
    # 전처리 전 원본 데이터를 높이(Z)에 따라 색상 매핑하여 표시.
    # 수직통로 구간은 빨간 삼각형으로 별도 표시.
    fig = plt.figure(figsize=(14, 10))
    ax = fig.add_subplot(111, projection='3d')

    # 평면 포인트: Z값으로 컬러맵 적용
    flat_pts = raw[~stair_mask]
    stair_pts = raw[stair_mask]
    ax.scatter(flat_pts[:, 0], flat_pts[:, 1], flat_pts[:, 2],
               c=flat_pts[:, 2], cmap='viridis', s=3, alpha=0.6, label='Floor')

    # 수직통로 포인트: 빨간 삼각형
    if len(stair_pts) > 0:
        ax.scatter(stair_pts[:, 0], stair_pts[:, 1], stair_pts[:, 2],
                   c='red', s=8, alpha=0.8, label='Vertical', marker='^')

    # 시작/끝점 강조
    ax.scatter(*raw[0], c='lime', s=100, marker='*', zorder=5, label='Start')
    ax.scatter(*raw[-1], c='red', s=100, marker='*', zorder=5, label='End')

    ax.set_xlabel('X (m)')
    ax.set_ylabel('Y (m)')
    ax.set_zlabel('Z (m)')
    ax.set_title(f'Raw Trajectory ({len(raw)} points)', fontsize=14, fontweight='bold')
    ax.legend(loc='upper left', fontsize=8)
    ax.view_init(elev=25, azim=-60)

    path1 = os.path.join(OUTPUT_DIR, '1_raw_trajectory_3d.png')
    fig.savefig(path1, dpi=150, bbox_inches='tight', facecolor='white')
    plt.close(fig)
    print(f"\n  saved: {path1}")

    # =====================================================
    # 이미지 2: 처리 후 층별 3D
    # =====================================================
    # 층별로 색상을 나누어 처리된 경로를 표시.
    # split_at_gaps(3.0m)로 세그먼트를 분리하여
    # 백트래킹 제거로 생긴 갭에서 대각선 아티팩트를 방지한다.
    fig = plt.figure(figsize=(14, 10))
    ax = fig.add_subplot(111, projection='3d')

    total_pts = 0
    for fl, positions in sorted(floors.items()):
        color = floor_colors.get(fl, default_color)
        # 갭(3m+)에서 세그먼트 분리 → 각각 따로 그려서 대각선 방지
        segments = split_at_gaps(positions, gap_threshold=3.0)
        for si, seg in enumerate(segments):
            label = f'{fl}F ({len(positions)}pts)' if si == 0 else None
            ax.plot(seg[:, 0], seg[:, 1], seg[:, 2],
                    color=color, linewidth=1.5, alpha=0.8, label=label)
            ax.scatter(seg[:, 0], seg[:, 1], seg[:, 2],
                       color=color, s=5, alpha=0.5)
        total_pts += len(positions)

    # 수직통로: 계단=대시, 엘리베이터=점선
    for p in passages:
        pts = np.array(p['positions'])
        style = '--' if p['type'] == 'STAIRCASE' else ':'
        ax.plot(pts[:, 0], pts[:, 1], pts[:, 2],
                color='red', linestyle=style, linewidth=2, alpha=0.9,
                label=f"{p['type']} ({p['direction']})")

    ax.set_xlabel('X (m)')
    ax.set_ylabel('Y (m)')
    ax.set_zlabel('Z (m)')
    ax.set_title(f'Processed ({total_pts}pts, {len(floors)} floors)',
                 fontsize=14, fontweight='bold')
    ax.legend(loc='upper left', fontsize=8)
    ax.view_init(elev=25, azim=-60)

    path2 = os.path.join(OUTPUT_DIR, '2_processed_floors_3d.png')
    fig.savefig(path2, dpi=150, bbox_inches='tight', facecolor='white')
    plt.close(fig)
    print(f"  saved: {path2}")

    # =====================================================
    # 이미지 3: 그래프 (노드+엣지) 3D
    # =====================================================
    # 최종 그래프를 노드 타입별 마커로 표시.
    # ENDPOINT=빨간 사각, JUNCTION=주황 다이아, WAYPOINT=파란 원.
    # 수평 엣지=연한 파란 실선, 수직 엣지=빨간 대시선.
    fig = plt.figure(figsize=(14, 10))
    ax = fig.add_subplot(111, projection='3d')

    # 노드 ID→데이터 매핑 (엣지 그릴 때 좌표 조회용)
    node_map = {n['id']: n for n in nodes}

    # 노드 타입별 시각 스타일
    type_styles = {
        'ENDPOINT':  {'color': 'red',    's': 60, 'marker': 's', 'label': 'ENDPOINT'},
        'JUNCTION':  {'color': 'orange', 's': 50, 'marker': 'D', 'label': 'JUNCTION'},
        'WAYPOINT':  {'color': '#2196F3','s': 15, 'marker': 'o', 'label': 'WAYPOINT'},
    }

    # 노드 플롯 (범례 중복 방지를 위해 타입별 첫 번째만 label 부여)
    plotted_types = set()
    for node in nodes:
        ntype = node.get('type', 'WAYPOINT')
        style = type_styles.get(ntype, type_styles['WAYPOINT'])
        label = style['label'] if ntype not in plotted_types else None
        ax.scatter(node['x'], node['y'], node['z'],
                   c=style['color'], s=style['s'], marker=style['marker'],
                   alpha=0.8, label=label, zorder=5)
        plotted_types.add(ntype)

    # 엣지 플롯
    for edge in edges:
        n1 = node_map.get(edge['from_node_id'])
        n2 = node_map.get(edge['to_node_id'])
        if n1 and n2:
            etype = edge.get('edge_type', 'HORIZONTAL')
            if 'VERTICAL' in etype:
                # 수직 엣지 (층간 연결): 빨간 대시선
                ax.plot([n1['x'], n2['x']], [n1['y'], n2['y']], [n1['z'], n2['z']],
                        color='red', linewidth=2, linestyle='--', alpha=0.8)
            else:
                # 수평 엣지 (같은 층): 연한 파란 실선
                ax.plot([n1['x'], n2['x']], [n1['y'], n2['y']], [n1['z'], n2['z']],
                        color='#90CAF9', linewidth=0.8, alpha=0.6)

    ax.set_xlabel('X (m)')
    ax.set_ylabel('Y (m)')
    ax.set_zlabel('Z (m)')
    ax.set_title(f'Path Graph (nodes {len(nodes)}, edges {len(edges)})',
                 fontsize=14, fontweight='bold')
    ax.legend(loc='upper left', fontsize=8)
    ax.view_init(elev=25, azim=-60)

    path3 = os.path.join(OUTPUT_DIR, '3_graph_3d.png')
    fig.savefig(path3, dpi=150, bbox_inches='tight', facecolor='white')
    plt.close(fig)
    print(f"  saved: {path3}")

    # =====================================================
    # 이미지 4: 비교 (원본 vs 처리 후) 3D
    # =====================================================
    # 좌우 배치로 원본과 처리 후를 나란히 비교.
    # 축 범위를 동기화하여 스케일을 맞춘다.
    fig = plt.figure(figsize=(20, 9))

    # 왼쪽: 원본 궤적
    ax1 = fig.add_subplot(121, projection='3d')
    ax1.scatter(raw[:, 0], raw[:, 1], raw[:, 2],
                c=raw[:, 2], cmap='viridis', s=3, alpha=0.5)
    ax1.set_xlabel('X (m)')
    ax1.set_ylabel('Y (m)')
    ax1.set_zlabel('Z (m)')
    ax1.set_title(f'Raw ({len(raw)}pts)', fontsize=13, fontweight='bold')
    ax1.view_init(elev=25, azim=-60)

    # 오른쪽: 처리 후 (갭 인식 플롯)
    ax2 = fig.add_subplot(122, projection='3d')
    for fl, positions in sorted(floors.items()):
        color = floor_colors.get(fl, default_color)
        segments = split_at_gaps(positions, gap_threshold=3.0)
        for si, seg in enumerate(segments):
            label = f'{fl}F' if si == 0 else None
            ax2.plot(seg[:, 0], seg[:, 1], seg[:, 2],
                     color=color, linewidth=1.5, alpha=0.8, label=label)
            ax2.scatter(seg[:, 0], seg[:, 1], seg[:, 2],
                        color=color, s=5, alpha=0.5)

    for p in passages:
        pts = np.array(p['positions'])
        ax2.plot(pts[:, 0], pts[:, 1], pts[:, 2],
                 color='red', linestyle='--', linewidth=2, alpha=0.8)

    total_processed = sum(len(p) for p in floors.values())
    ax2.set_xlabel('X (m)')
    ax2.set_ylabel('Y (m)')
    ax2.set_zlabel('Z (m)')
    ax2.set_title(f'Processed ({total_processed}pts)', fontsize=13, fontweight='bold')
    ax2.legend(loc='upper left', fontsize=8)
    ax2.view_init(elev=25, azim=-60)

    # 축 범위 동기화 (양쪽 동일 스케일)
    for ax in [ax1, ax2]:
        ax.set_xlim(raw[:, 0].min() - 1, raw[:, 0].max() + 1)
        ax.set_ylim(raw[:, 1].min() - 1, raw[:, 1].max() + 1)
        ax.set_zlim(raw[:, 2].min() - 1, raw[:, 2].max() + 1)

    fig.suptitle('Raw vs Processed', fontsize=16, fontweight='bold', y=0.98)
    path4 = os.path.join(OUTPUT_DIR, '4_comparison_3d.png')
    fig.savefig(path4, dpi=150, bbox_inches='tight', facecolor='white')
    plt.close(fig)
    print(f"  saved: {path4}")

    return [path1, path2, path3, path4]


if __name__ == '__main__':
    result = run_pipeline()
    print("\n" + "=" * 60)
    print("  3D Visualization")
    print("=" * 60)
    paths = visualize_3d(result)
    print("\n  Done!")
    print(f"  Output: {OUTPUT_DIR}")
