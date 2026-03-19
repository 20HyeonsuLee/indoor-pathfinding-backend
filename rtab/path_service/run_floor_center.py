"""
바닥 중앙 기반 경로 그래프 생성 파이프라인

[기존 pose 기반과의 차이]
기존: 카메라 궤적 → 스무딩 → 중복 제거 → 그래프
이것: 포인트 클라우드 바닥 범위 → 중앙 노드 → 허브 정리 → 그래프

[파이프라인]
① 데이터 추출: pose + 포인트 클라우드
② 바닥 중앙 노드 생성 (모든 프레임)
③ 전처리: 이상치 제거 + 스무딩
④ 수직통로 감지 + 층 분리
⑤ 허브 탐지 및 정리
⑥ 복도 구간 단순화 (KD-Tree + RDP)
⑦ 그래프 조립
"""
import sys
import os
import numpy as np

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from mpl_toolkits.mplot3d import Axes3D

sys.path.insert(0, os.path.dirname(__file__))

from services.floor_center import extract_per_frame_data, compute_floor_center_nodes, merge_nearby_nodes
from services.hub_detection import find_hub_zones, cleanup_hubs
from services.vertical_detector import detect_stairs_first, separate_floors, assign_floors_to_stairs
from services.smoothing import smooth_path, remove_outliers
from services.deduplication import deduplicate_path
from services.path_flattening import snap_to_lines
from services.junction_detection import build_path_graph, merge_floor_graphs

DB_PATH = os.path.join(os.path.dirname(__file__), '..', 'db', 'hub.db')
OUTPUT_DIR = os.path.join(os.path.dirname(__file__), '..', 'exported_images', 'floor_center')
os.makedirs(OUTPUT_DIR, exist_ok=True)


def run_pipeline():
    print("=" * 60)
    print("  바닥 중앙 기반 파이프라인")
    print("=" * 60)

    # ─────────────────────────────────────────────────────────
    # ① 데이터 추출
    # ─────────────────────────────────────────────────────────
    print("\n[1/7] 프레임별 데이터 추출...")
    frames = extract_per_frame_data(DB_PATH)
    n_with_cloud = sum(1 for f in frames if f['points_local'] is not None)
    print(f"  프레임: {len(frames)}개 (포인트클라우드: {n_with_cloud}개)")

    # pose 궤적 (비교용)
    pose_positions = np.array([f['position'] for f in frames])

    # ─────────────────────────────────────────────────────────
    # ② 바닥 중앙 노드 생성 (XY만, Z는 아직 pose 유지)
    # ─────────────────────────────────────────────────────────
    print("\n[2/7] 바닥 중앙 노드 생성...")
    raw_nodes = compute_floor_center_nodes(frames)
    n_fallback = sum(1 for n in raw_nodes if n['is_fallback'])
    print(f"  노드: {len(raw_nodes)}개 (폴백: {n_fallback}개)")

    if n_fallback > 0:
        fb_pct = 100 * n_fallback / len(raw_nodes)
        print(f"  ⚠ 바닥 미감지 {fb_pct:.0f}% → pose 위치로 폴백")

    widths = [n['floor_width'] for n in raw_nodes if n['floor_width'] > 0]
    if widths:
        print(f"  바닥 폭: {np.mean(widths):.1f}m 평균 "
              f"({np.min(widths):.1f}~{np.max(widths):.1f}m)")

    # XY는 바닥 중앙, Z는 원본 pose (수직통로 감지용)
    center_positions = np.array([n['position'] for n in raw_nodes])
    center_positions[:, 2] = pose_positions[:, 2]  # Z를 pose로 되돌림

    # ─────────────────────────────────────────────────────────
    # ③ 전처리: 이상치 제거 + XY 스무딩 (Z는 pose 원본 유지)
    # ─────────────────────────────────────────────────────────
    print("\n[3/7] 전처리...")
    cleaned = remove_outliers(center_positions, threshold_std=3.0)
    outlier_count = np.sum(np.any(cleaned != center_positions, axis=1))
    print(f"  이상치 {outlier_count}개 보간")

    smoothed = smooth_path(cleaned, sigma=1.0)
    smoothed[:, 2] = cleaned[:, 2]  # Z 보존 (수직통로 감지에 필요)
    print(f"  XY 스무딩 완료 (sigma=1.0)")

    # ─────────────────────────────────────────────────────────
    # ④ 수직통로 감지 + 층 분리 (pose Z 기반)
    # ─────────────────────────────────────────────────────────
    print("\n[4/7] 수직통로 감지 + 층 분리...")
    vertical_passages, stair_mask = detect_stairs_first(smoothed)
    print(f"  수직통로: {len(vertical_passages)}개")
    for p in vertical_passages:
        print(f"    {p['type']}: {p['z_start']:.1f}m → {p['z_end']:.1f}m ({p['direction']})")

    node_ids = [n['node_id'] for n in raw_nodes]
    floors_data = separate_floors(smoothed, node_ids, stair_mask=stair_mask,
                                   vertical_passages=vertical_passages)
    vertical_passages = assign_floors_to_stairs(vertical_passages, floors_data)

    if not floors_data:
        flat = smoothed[~stair_mask] if stair_mask.any() else smoothed
        floors_data = {1: {
            'positions': flat,
            'node_ids': node_ids,
            'z_mean': float(np.mean(flat[:, 2])),
            'point_count': len(flat)
        }}

    for fl, data in floors_data.items():
        print(f"  {fl}층: {data['point_count']}개 포인트")

    # ─────────────────────────────────────────────────────────
    # ④-2: 층별 바닥 Z 고정
    # ─────────────────────────────────────────────────────────
    # 수직통로 감지가 끝났으므로 이제 Z를 층별 바닥 높이로 고정한다.
    # 각 층의 포인트클라우드에서 바닥 Z를 추정하여 사용.
    floor_z_values = {}
    for fl, data in floors_data.items():
        # 이 층 포인트의 바닥 Z = raw_nodes에서 이미 추정한 값 사용
        floor_indices = data.get('indices', [])
        floor_z_candidates = []
        for idx in floor_indices:
            if idx < len(raw_nodes):
                fz = raw_nodes[idx]['position'][2]
                if not np.isnan(fz):
                    floor_z_candidates.append(fz)

        if floor_z_candidates:
            floor_z_values[fl] = float(np.median(floor_z_candidates))
        else:
            floor_z_values[fl] = data['z_mean']
        print(f"  {fl}층 바닥 Z: {floor_z_values[fl]:.2f}m")

    # 층별 positions의 Z를 바닥 높이로 고정
    for fl, data in floors_data.items():
        data['positions'][:, 2] = floor_z_values[fl]

    # smoothed도 층별 Z 고정 (시각화용)
    for fl, data in floors_data.items():
        for idx in data.get('indices', []):
            if idx < len(smoothed):
                smoothed[idx, 2] = floor_z_values[fl]

    # raw_nodes 위치도 업데이트
    for i in range(len(raw_nodes)):
        raw_nodes[i]['position'] = smoothed[i]

    # ─────────────────────────────────────────────────────────
    # ⑤ 허브 탐지 및 정리
    # ─────────────────────────────────────────────────────────
    print("\n[5/7] 허브 탐지...")

    # 층별로 허브 탐지 (수직통로 제외)
    floor_hub_results = {}
    for fl, data in floors_data.items():
        # 이 층에 속하는 노드의 원본 인덱스 찾기
        floor_indices = data.get('indices', np.arange(len(data['positions'])))

        # 해당 층의 raw_nodes 서브셋 (인덱스 매핑)
        floor_nodes = []
        index_map = {}  # floor_nodes 인덱스 → 원본 인덱스
        for local_i, global_i in enumerate(floor_indices):
            if global_i < len(raw_nodes):
                floor_nodes.append(raw_nodes[global_i])
                index_map[len(floor_nodes) - 1] = global_i

        if len(floor_nodes) < 10:
            floor_hub_results[fl] = {'keep_indices': list(range(len(floor_nodes))),
                                      'hub_edges': [], 'hubs': []}
            continue

        hubs = find_hub_zones(floor_nodes)
        if hubs:
            keep_local, hub_edges = cleanup_hubs(floor_nodes, hubs)
            # 로컬 인덱스 → 글로벌 인덱스 변환
            hub_edges_global = []
            for edge in hub_edges:
                edge_global = edge.copy()
                edge_global['from_idx'] = index_map.get(edge['from_idx'], edge['from_idx'])
                edge_global['to_idx'] = index_map.get(edge['to_idx'], edge['to_idx'])
                hub_edges_global.append(edge_global)

            n_removed = len(floor_nodes) - len(keep_local)
            print(f"  {fl}층: 허브 {len(hubs)}개 발견, "
                  f"내부 노드 {n_removed}개 제거")
            floor_hub_results[fl] = {'keep_indices': keep_local,
                                      'hub_edges': hub_edges_global,
                                      'hubs': hubs}
        else:
            floor_hub_results[fl] = {'keep_indices': list(range(len(floor_nodes))),
                                      'hub_edges': [], 'hubs': []}
            print(f"  {fl}층: 허브 없음")

    # ─────────────────────────────────────────────────────────
    # ⑥ 복도 구간 단순화 (중복 병합 → 중복 제거 → RDP)
    # ─────────────────────────────────────────────────────────
    print("\n[6/7] 복도 구간 단순화...")
    flattened_floors = {}
    for fl, data in floors_data.items():
        positions = data['positions']
        if len(positions) < 3:
            flattened_floors[fl] = positions
            continue

        # 이 층에 해당하는 raw_nodes 찾기 (floor_width 기반 병합용)
        floor_indices = data.get('indices', np.arange(len(positions)))
        floor_raw_nodes = []
        for idx in floor_indices:
            if idx < len(raw_nodes):
                node = raw_nodes[idx].copy()
                node['position'] = np.array([
                    positions[list(floor_indices).index(idx), 0] if hasattr(floor_indices, '__iter__') else positions[idx, 0],
                    positions[list(floor_indices).index(idx), 1] if hasattr(floor_indices, '__iter__') else positions[idx, 1],
                    data['positions'][list(floor_indices).index(idx), 2]
                ])
                floor_raw_nodes.append(node)

        # 중복 병합: 가까운 노드 중 바닥 폭 최대만 유지
        if floor_raw_nodes:
            before = len(floor_raw_nodes)
            merged_nodes = merge_nearby_nodes(floor_raw_nodes, merge_radius=1.0)
            merged_positions = np.array([n['position'] for n in merged_nodes])
            print(f"  {fl}층 중복 병합: {before} → {len(merged_nodes)}개")
        else:
            merged_positions = positions

        if len(merged_positions) < 3:
            flattened_floors[fl] = merged_positions
            continue

        deduped = deduplicate_path(merged_positions, distance_threshold=0.5)
        snapped = snap_to_lines(deduped, epsilon=0.5, point_spacing=0.5)
        flattened_floors[fl] = snapped
        print(f"  {fl}층 RDP+보간: {len(merged_positions)} → {len(snapped)}개")

    # ─────────────────────────────────────────────────────────
    # ⑦ 그래프 조립
    # ─────────────────────────────────────────────────────────
    print("\n[7/7] 그래프 조립...")
    floor_graphs = {}
    for fl, positions in flattened_floors.items():
        if len(positions) < 2:
            continue
        nodes, edges = build_path_graph(positions, floor_level=fl)
        floor_graphs[fl] = (nodes, edges)
        print(f"  {fl}층: 노드 {len(nodes)}개, 엣지 {len(edges)}개")

    all_nodes, all_edges = merge_floor_graphs(floor_graphs, vertical_passages)
    print(f"\n  전체 그래프: 노드 {len(all_nodes)}개, 엣지 {len(all_edges)}개")

    return {
        'pose_positions': pose_positions,
        'center_positions': center_positions,
        'smoothed': smoothed,
        'stair_mask': stair_mask,
        'vertical_passages': vertical_passages,
        'floors_data': floors_data,
        'flattened_floors': flattened_floors,
        'all_nodes': all_nodes,
        'all_edges': all_edges,
        'raw_nodes': raw_nodes,
        'floor_hub_results': floor_hub_results,
    }


# =============================================================================
# 시각화
# =============================================================================

def visualize(result):
    """파이프라인 결과를 3장의 이미지로 시각화한다."""

    pose = result['pose_positions']
    center = result['center_positions']
    smoothed = result['smoothed']
    stair_mask = result['stair_mask']
    floors = result['flattened_floors']
    nodes = result['all_nodes']
    edges = result['all_edges']
    raw_nodes = result['raw_nodes']

    floor_colors = {1: '#2196F3', 2: '#4CAF50', 3: '#FF9800', 4: '#E91E63'}
    default_color = '#607D8B'

    # =====================================================
    # 이미지 1: pose vs 바닥 중앙 비교
    # =====================================================
    fig = plt.figure(figsize=(16, 7))

    # 2D 탑뷰 비교
    ax1 = fig.add_subplot(121)
    ax1.plot(pose[:, 0], pose[:, 1], 'r-', alpha=0.4, linewidth=1, label='Pose trajectory')
    ax1.plot(center[:, 0], center[:, 1], 'b-', alpha=0.6, linewidth=1.5, label='Floor center')
    ax1.scatter(pose[0, 0], pose[0, 1], c='lime', s=80, marker='*', zorder=5, label='Start')

    # 바닥 폭 표시 (10프레임마다)
    for i in range(0, len(raw_nodes), 10):
        n = raw_nodes[i]
        if n['floor_width'] > 0 and not n['is_fallback']:
            pos = n['position']
            d = n['direction']
            perp = np.array([-d[1], d[0]])
            hw = n['floor_width'] / 2
            p1 = pos[:2] + hw * perp
            p2 = pos[:2] - hw * perp
            ax1.plot([p1[0], p2[0]], [p1[1], p2[1]], 'g-', alpha=0.3, linewidth=0.8)

    ax1.set_xlabel('X (m)')
    ax1.set_ylabel('Y (m)')
    ax1.set_title('Pose vs Floor Center (Top View)', fontweight='bold')
    ax1.legend(fontsize=8)
    ax1.set_aspect('equal')
    ax1.grid(True, alpha=0.3)

    # 3D 비교
    ax2 = fig.add_subplot(122, projection='3d')
    ax2.plot(pose[:, 0], pose[:, 1], pose[:, 2],
             'r-', alpha=0.3, linewidth=1, label='Pose')
    ax2.plot(smoothed[:, 0], smoothed[:, 1], smoothed[:, 2],
             'b-', alpha=0.6, linewidth=1.5, label='Floor center')
    ax2.set_xlabel('X (m)')
    ax2.set_ylabel('Y (m)')
    ax2.set_zlabel('Z (m)')
    ax2.set_title('3D Comparison', fontweight='bold')
    ax2.legend(fontsize=8)
    ax2.view_init(elev=25, azim=-60)

    fig.suptitle(f'Pose ({len(pose)}pts) vs Floor Center ({len(center)}pts)',
                 fontsize=14, fontweight='bold')

    p1 = os.path.join(OUTPUT_DIR, '1_pose_vs_center.png')
    fig.savefig(p1, dpi=150, bbox_inches='tight', facecolor='white')
    plt.close(fig)
    print(f"  saved: {p1}")

    # =====================================================
    # 이미지 2: 층별 처리 결과
    # =====================================================
    fig = plt.figure(figsize=(14, 10))
    ax = fig.add_subplot(111, projection='3d')

    total_pts = 0
    for fl, positions in sorted(floors.items()):
        color = floor_colors.get(fl, default_color)
        ax.plot(positions[:, 0], positions[:, 1], positions[:, 2],
                color=color, linewidth=1.5, alpha=0.8, label=f'{fl}F ({len(positions)}pts)')
        ax.scatter(positions[:, 0], positions[:, 1], positions[:, 2],
                   color=color, s=5, alpha=0.5)
        total_pts += len(positions)

    # 수직통로: 시작/끝점만 빨간 마커로 표시
    for pi, p in enumerate(result['vertical_passages']):
        pts = np.array(p['positions'])
        label_entry = 'Stair entry' if pi == 0 else None
        label_exit = 'Stair exit' if pi == 0 else None
        ax.scatter(pts[0, 0], pts[0, 1], pts[0, 2],
                   c='red', s=80, marker='^', zorder=5, label=label_entry)
        ax.scatter(pts[-1, 0], pts[-1, 1], pts[-1, 2],
                   c='red', s=80, marker='v', zorder=5, label=label_exit)

    ax.set_xlabel('X (m)')
    ax.set_ylabel('Y (m)')
    ax.set_zlabel('Z (m)')
    ax.set_title(f'Processed ({total_pts}pts, {len(floors)} floors)',
                 fontsize=14, fontweight='bold')
    ax.legend(loc='upper left', fontsize=8)
    ax.view_init(elev=25, azim=-60)

    p2 = os.path.join(OUTPUT_DIR, '2_processed_floors.png')
    fig.savefig(p2, dpi=150, bbox_inches='tight', facecolor='white')
    plt.close(fig)
    print(f"  saved: {p2}")

    # =====================================================
    # 이미지 3: 최종 그래프
    # =====================================================
    fig = plt.figure(figsize=(14, 10))
    ax = fig.add_subplot(111, projection='3d')

    node_map = {n['id']: n for n in nodes}

    type_styles = {
        'ENDPOINT':      {'color': 'red',    's': 60, 'marker': 's'},
        'JUNCTION':      {'color': 'orange', 's': 50, 'marker': 'D'},
        'WAYPOINT':      {'color': '#2196F3','s': 15, 'marker': 'o'},
        'PASSAGE_ENTRY': {'color': 'red',    's': 70, 'marker': '^'},
        'PASSAGE_EXIT':  {'color': 'red',    's': 70, 'marker': 'v'},
    }

    plotted_types = set()
    for node in nodes:
        ntype = node.get('type', 'WAYPOINT')
        style = type_styles.get(ntype, type_styles['WAYPOINT'])
        label = ntype if ntype not in plotted_types else None
        ax.scatter(node['x'], node['y'], node['z'],
                   c=style['color'], s=style['s'], marker=style['marker'],
                   alpha=0.8, label=label, zorder=5)
        plotted_types.add(ntype)

    for edge in edges:
        n1 = node_map.get(edge['from_node_id'])
        n2 = node_map.get(edge['to_node_id'])
        if n1 and n2:
            etype = edge.get('edge_type', 'HORIZONTAL')
            if 'VERTICAL' in etype:
                pass  # 수직 엣지는 선 없이 노드 마커만 표시
            else:
                ax.plot([n1['x'], n2['x']], [n1['y'], n2['y']], [n1['z'], n2['z']],
                        color='#90CAF9', linewidth=0.8, alpha=0.6)

    ax.set_xlabel('X (m)')
    ax.set_ylabel('Y (m)')
    ax.set_zlabel('Z (m)')
    ax.set_title(f'Graph (nodes {len(nodes)}, edges {len(edges)})',
                 fontsize=14, fontweight='bold')
    ax.legend(loc='upper left', fontsize=8)
    ax.view_init(elev=25, azim=-60)

    p3 = os.path.join(OUTPUT_DIR, '3_graph.png')
    fig.savefig(p3, dpi=150, bbox_inches='tight', facecolor='white')
    plt.close(fig)
    print(f"  saved: {p3}")

    return [p1, p2, p3]


if __name__ == '__main__':
    result = run_pipeline()
    print("\n" + "=" * 60)
    print("  시각화")
    print("=" * 60)
    paths = visualize(result)
    print(f"\n  Done! Output: {OUTPUT_DIR}")
