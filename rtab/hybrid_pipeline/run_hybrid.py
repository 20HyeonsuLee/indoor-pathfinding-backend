"""
하이브리드 실내 경로 그래프 생성 파이프라인

[핵심 아이디어]
  궤적(trajectory) → 층 분리 + 계단 감지 (잘함)
  포인트클라우드(pointcloud) → 통로 중심선 추출 (잘함)
  둘을 합치면 → 깔끔한 다층 실내 경로 그래프

[파이프라인]
  Phase A: 궤적 분석
    1. DB에서 카메라 궤적 추출
    2. XY 스무딩 (Z 보존)
    3. 계단/엘리베이터 감지
    4. 층 분리 (계단 힌트 활용)

  Phase B: 포인트클라우드 슬라이싱
    5. DB에서 3D 포인트클라우드 추출
    6. 궤적 층 분리 결과로 포인트클라우드를 층별로 자르기

  Phase C: 층별 경로 추출
    7. 점유 격자 생성
    8. 통행 가능 영역 정제
    9. 스켈레톤화 → 통로 중심선
   10. 그래프 추출 (노드/엣지)
   11. 허브(로비) 감지

  Phase D: 층간 연결
   12. 계단/엘리베이터로 층간 VERTICAL 엣지 생성

  Phase E: 시각화
"""
import sys
import os
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from mpl_toolkits.mplot3d import Axes3D

# 모듈 경로 등록
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'path_service'))
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'pointcloud_pipeline'))

# 궤적 파이프라인
from services.extraction import extract_trajectory_from_db
from services.smoothing import remove_outliers, smooth_path
from services.vertical_detector import (
    detect_stairs_first, separate_floors, assign_floors_to_stairs
)

# 포인트클라우드 파이프라인
from run_hub import (
    extract_pointcloud,
    create_occupancy_grid,
    extract_walkable_area,
    skeletonize,
    skeleton_to_graph,
    detect_hubs,
    find_corridor_entries,
    create_hub_edges,
    CELL_SIZE,
)

DB_PATH = os.path.join(os.path.dirname(__file__), '..', 'db', 'hub.db')
OUTPUT_DIR = os.path.join(os.path.dirname(__file__), '..', 'exported_images', 'hybrid')
os.makedirs(OUTPUT_DIR, exist_ok=True)


# =============================================================================
# Phase B: 포인트클라우드 슬라이싱
# =============================================================================

def slice_pointcloud_by_floors(world_points, cam_positions, trajectory_floors, margin=1.5):
    """
    궤적 기반 층 분리 결과를 사용하여 포인트클라우드를 층별로 자른다.

    [슬라이싱 전략]
    인접 층 사이의 중간점을 경계로 사용한다.
    이렇게 하면 포인트가 두 층에 중복 할당되지 않고,
    가변적인 층고에도 자연스럽게 대응한다.

        3층 z_mean=12m  ─────────────
                            ↕ 경계 = (12+4)/2 = 8m
        2층 z_mean=4m   ─────────────
                            ↕ 경계 = (4+0)/2 = 2m
        1층 z_mean=0m   ─────────────

    Args:
        world_points: [M, 3] 전체 3D 포인트
        cam_positions: [K, 3] 카메라 위치
        trajectory_floors: separate_floors()의 반환값
        margin: 최외곽 층의 상하 여유 (미터)

    Returns:
        층별 {'points', 'cam_positions', 'z_peak', 'z_range'} 딕셔너리
    """
    sorted_floors = sorted(trajectory_floors.items(), key=lambda kv: kv[1]['z_mean'])
    floor_slices = {}

    for i, (floor_num, floor_data) in enumerate(sorted_floors):
        # 경계 계산: 인접 층과의 중간점
        if i == 0:
            z_lower = floor_data['z_min'] - margin
        else:
            prev_z = sorted_floors[i - 1][1]['z_mean']
            z_lower = (prev_z + floor_data['z_mean']) / 2

        if i == len(sorted_floors) - 1:
            z_upper = floor_data['z_max'] + margin
        else:
            next_z = sorted_floors[i + 1][1]['z_mean']
            z_upper = (floor_data['z_mean'] + next_z) / 2

        # 포인트클라우드 슬라이싱
        pt_mask = (world_points[:, 2] >= z_lower) & (world_points[:, 2] <= z_upper)
        cam_mask = (cam_positions[:, 2] >= z_lower) & (cam_positions[:, 2] <= z_upper)

        floor_slices[floor_num] = {
            'points': world_points[pt_mask],
            'cam_positions': cam_positions[cam_mask],
            'z_peak': floor_data['z_mean'],
            'z_range': (z_lower, z_upper),
        }

    return floor_slices


# =============================================================================
# Phase D: 층간 연결
# =============================================================================

def connect_floors_via_passages(floor_results, vertical_passages):
    """
    계단/엘리베이터 구간으로 층간 VERTICAL 엣지를 생성한다.

    [연결 전략]
    1. 각 passage의 시작 XY → from_floor 그래프에서 가장 가까운 노드
    2. 각 passage의 끝 XY → to_floor 그래프에서 가장 가까운 노드
    3. 두 노드 사이에 VERTICAL 엣지 생성

    Args:
        floor_results: 층별 처리 결과 (nodes 포함)
        vertical_passages: assign_floors_to_stairs() 결과

    Returns:
        새로운 VERTICAL 엣지 리스트
    """
    vertical_edges = []

    for passage in vertical_passages:
        from_floor = passage.get('from_floor')
        to_floor = passage.get('to_floor')

        if from_floor is None or to_floor is None:
            continue
        if from_floor not in floor_results or to_floor not in floor_results:
            continue
        if from_floor == to_floor:
            continue

        positions = passage['positions']
        entry_xy = positions[0][:2]
        exit_xy = positions[-1][:2]

        # from_floor에서 가장 가까운 노드
        entry_node = _find_nearest_node_by_xy(
            floor_results[from_floor]['nodes'], entry_xy
        )
        # to_floor에서 가장 가까운 노드
        exit_node = _find_nearest_node_by_xy(
            floor_results[to_floor]['nodes'], exit_xy
        )

        if entry_node and exit_node:
            vertical_edges.append({
                'from': entry_node['id'],
                'to': exit_node['id'],
                'distance': float(passage['z_displacement']),
                'type': f"VERTICAL_{passage['type']}",
            })

    return vertical_edges


def _find_nearest_node_by_xy(nodes, target_xy):
    """XY 좌표로 가장 가까운 노드를 찾는다."""
    if not nodes:
        return None

    best_node = None
    best_dist = float('inf')

    for node in nodes:
        dx = node['x'] - target_xy[0]
        dy = node['y'] - target_xy[1]
        dist = np.sqrt(dx * dx + dy * dy)
        if dist < best_dist:
            best_dist = dist
            best_node = node

    return best_node


# =============================================================================
# Phase E: 시각화
# =============================================================================

def visualize_hybrid(floor_results, vertical_passages, all_nodes, all_edges,
                     world_points, cam_positions, trajectory, stair_mask):
    """하이브리드 파이프라인 결과 시각화 (4장)"""

    floor_colors = ['#2196F3', '#4CAF50', '#FF9800', '#E91E63', '#9C27B0']

    # --- 이미지 1: 3D 포인트클라우드 + 궤적 + 계단 ---
    fig = plt.figure(figsize=(14, 10))
    ax = fig.add_subplot(111, projection='3d')

    if len(world_points) > 8000:
        idx = np.random.choice(len(world_points), 8000, replace=False)
        pts = world_points[idx]
    else:
        pts = world_points
    ax.scatter(pts[:, 0], pts[:, 1], pts[:, 2],
               c=pts[:, 2], cmap='viridis', s=1, alpha=0.2)

    # 궤적 (층별 색상)
    flat_mask = ~stair_mask
    ax.plot(trajectory[flat_mask, 0], trajectory[flat_mask, 1], trajectory[flat_mask, 2],
            'b-', linewidth=1, alpha=0.5, label='Floor trajectory')
    ax.plot(trajectory[stair_mask, 0], trajectory[stair_mask, 1], trajectory[stair_mask, 2],
            'r.', markersize=3, alpha=0.8, label='Stair points')

    ax.set_xlabel('X (m)')
    ax.set_ylabel('Y (m)')
    ax.set_zlabel('Z (m)')
    ax.set_title('Hybrid: Pointcloud + Trajectory', fontweight='bold')
    ax.legend()
    ax.view_init(elev=25, azim=-60)
    p1 = os.path.join(OUTPUT_DIR, '1_pointcloud_trajectory.png')
    fig.savefig(p1, dpi=150, bbox_inches='tight', facecolor='white')
    plt.close(fig)
    print(f"  saved: {p1}")

    # --- 이미지 2: 층별 점유격자 + 스켈레톤 + 그래프 ---
    n_floors = len(floor_results)
    fig, axes = plt.subplots(n_floors, 3, figsize=(18, 6 * n_floors),
                             squeeze=False)

    for idx, (floor_num, fdata) in enumerate(sorted(floor_results.items())):
        grid = fdata['grid']
        walkable = fdata['walkable']
        skel = fdata['skeleton']
        fnodes = fdata['nodes']
        fedges = fdata['edges']

        # 점유 격자
        ax = axes[idx, 0]
        display = np.zeros((*grid.shape, 3))
        display[grid == 0] = [0.9, 0.9, 0.9]
        display[grid == 1] = [0.2, 0.2, 0.2]
        display[grid == 2] = [0.6, 0.85, 1.0]
        ax.imshow(display, origin='lower')
        z_range = fdata.get('z_range', (0, 0))
        ax.set_title(f'{floor_num}F Occupancy (Z:{z_range[0]:.1f}~{z_range[1]:.1f}m)',
                     fontweight='bold')

        # 스켈레톤
        ax = axes[idx, 1]
        display2 = np.zeros((*walkable.shape, 3))
        display2[walkable == 1] = [0.85, 0.95, 0.85]
        display2[skel == 1] = [1.0, 0.3, 0.0]
        ax.imshow(display2, origin='lower')
        ax.set_title(f'{floor_num}F Skeleton', fontweight='bold')

        # 그래프
        ax = axes[idx, 2]
        ax.imshow(display, origin='lower', alpha=0.3)

        for hub in fdata.get('hubs', []):
            hub_overlay = np.zeros((*grid.shape, 4))
            hub_overlay[hub['mask']] = [1.0, 0.3, 0.3, 0.25]
            ax.imshow(hub_overlay, origin='lower')

        node_by_id = {n['id']: n for n in fnodes}
        for e in fedges:
            n1 = node_by_id.get(e['from'])
            n2 = node_by_id.get(e['to'])
            if not n1 or not n2 or 'grid_pos' not in n1 or 'grid_pos' not in n2:
                continue
            color = '#4CAF50' if e.get('type') == 'HUB' else '#42A5F5'
            style = '--' if e.get('type') == 'HUB' else '-'
            ax.plot([n1['grid_pos'][1], n2['grid_pos'][1]],
                    [n1['grid_pos'][0], n2['grid_pos'][0]],
                    color=color, linewidth=1.5, alpha=0.8, linestyle=style)

        for n in fnodes:
            if 'grid_pos' not in n:
                continue
            colors = {'HUB_ENTRY': '#4CAF50', 'ENDPOINT': 'red'}
            c = colors.get(n['type'], 'orange')
            s = 50 if n['type'] in colors else 25
            ax.scatter(n['grid_pos'][1], n['grid_pos'][0],
                       c=c, s=s, zorder=5, edgecolors='black', linewidths=0.5)

        ax.set_title(f'{floor_num}F Graph (N={len(fnodes)}, E={len(fedges)})',
                     fontweight='bold')

    plt.tight_layout()
    p2 = os.path.join(OUTPUT_DIR, '2_floor_analysis.png')
    fig.savefig(p2, dpi=150, bbox_inches='tight', facecolor='white')
    plt.close(fig)
    print(f"  saved: {p2}")

    # --- 이미지 3: 전체 3D 그래프 (층간 연결 포함) ---
    fig = plt.figure(figsize=(14, 10))
    ax = fig.add_subplot(111, projection='3d')

    node_by_id = {n['id']: n for n in all_nodes}

    # 층별 노드
    for floor_num, fdata in sorted(floor_results.items()):
        color = floor_colors[(floor_num - 1) % len(floor_colors)]
        fnodes = fdata['nodes']
        for n in fnodes:
            marker_size = 50 if n['type'] in ('ENDPOINT', 'HUB_ENTRY') else 20
            ax.scatter(n['x'], n['y'], n['z'], c=color, s=marker_size, zorder=5)

    # 엣지
    for e in all_edges:
        n1 = node_by_id.get(e['from'])
        n2 = node_by_id.get(e['to'])
        if not n1 or not n2:
            continue
        edge_type = e.get('type', '')
        if 'VERTICAL' in edge_type:
            ax.plot([n1['x'], n2['x']], [n1['y'], n2['y']], [n1['z'], n2['z']],
                    color='red', linewidth=2.5, alpha=0.9, linestyle='--',
                    label=edge_type if edge_type not in ax.get_legend_handles_labels()[1] else '')
        elif edge_type == 'HUB':
            ax.plot([n1['x'], n2['x']], [n1['y'], n2['y']], [n1['z'], n2['z']],
                    color='#4CAF50', linewidth=1.5, alpha=0.7, linestyle='--')
        else:
            ax.plot([n1['x'], n2['x']], [n1['y'], n2['y']], [n1['z'], n2['z']],
                    color='#42A5F5', linewidth=1, alpha=0.6)

    # 층 라벨
    for floor_num, fdata in sorted(floor_results.items()):
        color = floor_colors[(floor_num - 1) % len(floor_colors)]
        n_nodes = len(fdata['nodes'])
        ax.text2D(0.02, 0.95 - (floor_num - 1) * 0.05,
                  f'{floor_num}F ({n_nodes} nodes)',
                  transform=ax.transAxes, color=color, fontweight='bold')

    ax.set_xlabel('X (m)')
    ax.set_ylabel('Y (m)')
    ax.set_zlabel('Z (m)')
    v_count = sum(1 for e in all_edges if 'VERTICAL' in e.get('type', ''))
    ax.set_title(f'Hybrid 3D Graph (N={len(all_nodes)}, E={len(all_edges)}, '
                 f'Vertical={v_count})', fontweight='bold')
    ax.legend()
    ax.view_init(elev=25, azim=-60)
    p3 = os.path.join(OUTPUT_DIR, '3_hybrid_graph_3d.png')
    fig.savefig(p3, dpi=150, bbox_inches='tight', facecolor='white')
    plt.close(fig)
    print(f"  saved: {p3}")

    return [p1, p2, p3]


# =============================================================================
# 메인 파이프라인
# =============================================================================

def main():
    print("=" * 60)
    print("  하이브리드 파이프라인 (궤적 + 포인트클라우드)")
    print("=" * 60)

    # ─── Phase A: 궤적 분석 ───
    print("\n[Phase A] 궤적 분석")

    print("  A1. 궤적 추출...")
    positions, node_ids = extract_trajectory_from_db(DB_PATH)
    print(f"      {len(positions)}개 포인트, Z: {positions[:,2].min():.1f}~{positions[:,2].max():.1f}m")

    print("  A2. XY 스무딩 (Z 보존)...")
    cleaned = remove_outliers(positions, threshold_std=3.0)
    smoothed = smooth_path(cleaned, sigma=1.5)
    smoothed[:, 2] = cleaned[:, 2]

    print("  A3. 계단/엘리베이터 감지...")
    vertical_passages, stair_mask = detect_stairs_first(smoothed)
    print(f"      수직통로 {len(vertical_passages)}개 감지")
    for p in vertical_passages:
        print(f"        {p['type']}: {p['z_start']:.1f}m → {p['z_end']:.1f}m ({p['direction']})")

    print("  A4. 층 분리...")
    trajectory_floors = separate_floors(
        smoothed, node_ids,
        stair_mask=stair_mask,
        vertical_passages=vertical_passages
    )
    vertical_passages = assign_floors_to_stairs(vertical_passages, trajectory_floors)
    print(f"      {len(trajectory_floors)}개 층 감지")
    for floor_num, fdata in sorted(trajectory_floors.items()):
        print(f"        {floor_num}층: Z평균={fdata['z_mean']:.2f}m "
              f"({fdata['z_min']:.1f}~{fdata['z_max']:.1f}m), "
              f"{fdata['point_count']}개 포인트")

    # ─── Phase B: 포인트클라우드 슬라이싱 ───
    print("\n[Phase B] 포인트클라우드 슬라이싱")

    print("  B1. 포인트클라우드 추출...")
    world_points, cam_positions = extract_pointcloud(DB_PATH)

    print("  B2. 층별 슬라이싱...")
    floor_slices = slice_pointcloud_by_floors(
        world_points, cam_positions, trajectory_floors, margin=1.5
    )
    for floor_num, fslice in sorted(floor_slices.items()):
        z_lo, z_hi = fslice['z_range']
        print(f"      {floor_num}층 (Z:{z_lo:.1f}~{z_hi:.1f}m): "
              f"포인트 {len(fslice['points'])}개, "
              f"카메라 {len(fslice['cam_positions'])}개")

    # ─── Phase C: 층별 경로 추출 ───
    print("\n[Phase C] 층별 경로 추출")

    all_nodes = []
    all_edges = []
    node_id_offset = 0
    floor_results = {}

    for floor_num in sorted(floor_slices.keys()):
        fslice = floor_slices[floor_num]
        print(f"\n  --- {floor_num}층 처리 ---")

        if len(fslice['points']) < 50:
            print(f"      포인트 부족 ({len(fslice['points'])}개), 스킵")
            continue
        if len(fslice['cam_positions']) < 3:
            print(f"      카메라 궤적 부족 ({len(fslice['cam_positions'])}개), 스킵")
            continue

        # C1. 점유 격자
        grid, x_min, y_min, w, h = create_occupancy_grid(fslice, CELL_SIZE)
        print(f"      격자: {w}x{h} ({w*CELL_SIZE:.0f}m x {h*CELL_SIZE:.0f}m), "
              f"OCCUPIED={int((grid==1).sum())}, FREE={int((grid==2).sum())}")

        # C2. 통행 가능 영역
        walkable = extract_walkable_area(grid)
        print(f"      통행 가능: {int(walkable.sum())}셀")

        if walkable.sum() < 10:
            print("      통행 가능 영역 부족, 스킵")
            continue

        # C3. 스켈레톤화
        skel = skeletonize(walkable)
        print(f"      스켈레톤: {int(skel.sum())}픽셀")

        if skel.sum() < 3:
            print("      스켈레톤 부족, 스킵")
            continue

        # C4. 그래프 추출
        nodes, edges = skeleton_to_graph(skel, x_min, y_min, CELL_SIZE, fslice['z_peak'])

        # ID 오프셋 (다층 병합용)
        for n in nodes:
            n['id'] += node_id_offset
            n['floor'] = floor_num
        for e in edges:
            e['from'] += node_id_offset
            e['to'] += node_id_offset

        # C5. 허브(로비) 감지
        hubs, dist_map = detect_hubs(walkable, CELL_SIZE, grid=grid)
        if hubs:
            entries_per_hub = [
                find_corridor_entries(hub, walkable, skel, CELL_SIZE)
                for hub in hubs
            ]
            hub_nodes, hub_edges, hub_info = create_hub_edges(
                hubs, entries_per_hub, nodes, edges,
                x_min, y_min, CELL_SIZE, fslice['z_peak']
            )
            for n in hub_nodes:
                n['id'] += node_id_offset
                n['floor'] = floor_num
            for e in hub_edges:
                e['from'] += node_id_offset
                e['to'] += node_id_offset
            nodes.extend(hub_nodes)
            edges.extend(hub_edges)
            for hi in hub_info:
                print(f"      허브: {hi['area_m2']:.1f}m², 진입점 {hi['n_entries']}개")
        else:
            hubs = []

        print(f"      그래프: 노드 {len(nodes)}개, 엣지 {len(edges)}개")

        floor_results[floor_num] = {
            'grid': grid, 'walkable': walkable, 'skeleton': skel,
            'nodes': nodes, 'edges': edges, 'hubs': hubs,
            'grid_origin': (x_min, y_min),
            'z_peak': fslice['z_peak'],
            'z_range': fslice['z_range'],
        }

        all_nodes.extend(nodes)
        all_edges.extend(edges)
        node_id_offset += len(nodes)

    # ─── Phase D: 층간 연결 ───
    print("\n[Phase D] 층간 연결")
    vertical_edges = connect_floors_via_passages(floor_results, vertical_passages)
    all_edges.extend(vertical_edges)
    print(f"  VERTICAL 엣지 {len(vertical_edges)}개 생성")
    for ve in vertical_edges:
        print(f"    {ve['type']}: node {ve['from']} → {ve['to']} "
              f"(거리 {ve['distance']:.1f}m)")

    # ─── 결과 요약 ───
    print(f"\n{'='*60}")
    print(f"  전체 그래프: 노드 {len(all_nodes)}개, 엣지 {len(all_edges)}개")
    print(f"  층: {len(floor_results)}개, 수직연결: {len(vertical_edges)}개")
    print(f"{'='*60}")

    # ─── Phase E: 시각화 ───
    print("\n[Phase E] 시각화")
    visualize_hybrid(
        floor_results, vertical_passages, all_nodes, all_edges,
        world_points, cam_positions, smoothed, stair_mask
    )

    print(f"\n  Done! Output: {OUTPUT_DIR}")


if __name__ == '__main__':
    main()
