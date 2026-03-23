"""
바닥 레이어 기반 평면도 시각화 스크립트

Ground 레이어(0.05~0.30m)로 바닥 영역을 추출하고,
통행 가능 영역, 스켈레톤+그래프를 시각화한다.
"""
import sys
import os
import numpy as np

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.colors import ListedColormap

sys.path.insert(0, os.path.dirname(__file__))

from services.pointcloud_graph import (
    extract_pointcloud, separate_floors_by_z,
    CELL_SIZE, GROUND_Z_MIN, GROUND_Z_MAX, GROUND_DENSITY_THRESHOLD,
    create_occupancy_grid, extract_ridge, remove_spurs,
    skeleton_to_graph,
)

DB_PATH = os.path.join(os.path.dirname(__file__), '..', 'db', 'hub.db')
OUTPUT_DIR = os.path.join(os.path.dirname(__file__), '..', 'exported_images', 'layers')
os.makedirs(OUTPUT_DIR, exist_ok=True)


def visualize_floor(floor_idx, fdata, peaks):
    """한 층의 바닥 평면도를 시각화한다."""
    points = fdata['points']
    cam_pos = fdata['cam_positions']
    z_peak = fdata.get('z_peak', np.median(points[:, 2]))

    # ── 격자 생성 ──
    floor_grid, x_min, y_min, width, height = create_occupancy_grid(fdata, CELL_SIZE)
    cam_pos = fdata['cam_positions']
    ridge = extract_ridge(floor_grid, cam_pos, x_min, y_min, CELL_SIZE)
    ridge = remove_spurs(ridge, CELL_SIZE)
    walkable = ridge  # 시각화 호환용

    # ── 시각화: 2x2 패널 ──
    fig, axes = plt.subplots(2, 2, figsize=(16, 16))
    floor_label = f"{floor_idx + 1}F (z_peak={z_peak:.1f}m)"
    occ_cmap = ListedColormap(['white', 'black'])

    # (0,0) 바닥 레이어 (raw)
    ax = axes[0, 0]
    ax.imshow(floor_grid, cmap=occ_cmap, origin='lower', interpolation='nearest')
    ax.set_title(f'Floor Layer ({GROUND_Z_MIN}~{GROUND_Z_MAX}m)\n'
                 f'density>={GROUND_DENSITY_THRESHOLD} | cells={floor_grid.sum()}',
                 fontsize=12, fontweight='bold')

    # (0,1) Ridge (복도 중심선)
    ax = axes[0, 1]
    ridge_display = np.zeros((*floor_grid.shape, 3), dtype=np.uint8)
    ridge_display[floor_grid > 0] = [60, 60, 60]  # 벽 = 진한 회색
    ridge_display[ridge > 0] = [255, 100, 100]     # ridge = 빨강
    ax.imshow(ridge_display, origin='lower', interpolation='nearest')
    ax.set_title(f'Ridge (corridor centerline)\ncells={ridge.sum()}',
                 fontsize=12, fontweight='bold')

    # (1,0) Ridge on Floor Layer
    ax = axes[1, 0]
    overlay = np.ones((*floor_grid.shape, 3), dtype=np.uint8) * 255
    overlay[floor_grid > 0] = [40, 40, 40]
    overlay[ridge > 0] = [255, 80, 80]
    ax.imshow(overlay, origin='lower', interpolation='nearest')
    ax.set_title(f'Floor + Ridge overlay',
                 fontsize=12, fontweight='bold')

    # (1,1) Graph
    ax = axes[1, 1]
    ax.imshow(overlay, origin='lower', interpolation='nearest')

    nodes, edges = skeleton_to_graph(ridge, x_min, y_min, CELL_SIZE, peaks[floor_idx])
    for node in nodes:
        gx = (node['x'] - x_min) / CELL_SIZE
        gy = (node['y'] - y_min) / CELL_SIZE
        color = {'ENDPOINT': 'blue', 'JUNCTION': 'orange'}.get(node['type'], '#2196F3')
        size = 30 if node['type'] in ('ENDPOINT', 'JUNCTION') else 8
        ax.scatter(gx, gy, c=color, s=size, zorder=5, edgecolors='white', linewidths=0.3)

    ax.set_title(f'Skeleton + Graph\n'
                 f'nodes={len(nodes)}, edges={len(edges)}',
                 fontsize=12, fontweight='bold')

    for ax in axes.flat:
        ax.set_aspect('equal')
        ax.tick_params(labelsize=7)

    fig.suptitle(f'{floor_label} — Floor Plan', fontsize=16, fontweight='bold')
    fig.tight_layout(rect=[0, 0, 1, 0.95])

    out_path = os.path.join(OUTPUT_DIR, f'floor_{floor_idx + 1}F.png')
    fig.savefig(out_path, dpi=150, bbox_inches='tight', facecolor='white')
    plt.close(fig)
    print(f"  saved: {out_path}")
    return out_path


def main():
    print("=" * 60)
    print("  Floor Plan Visualization")
    print("=" * 60)

    print("\n[1/2] 포인트클라우드 추출 + 층 분리...")
    world_points, cam_positions = extract_pointcloud(DB_PATH)
    floors, peaks = separate_floors_by_z(world_points, cam_positions)

    print(f"\n[2/2] 평면도 시각화...")
    for fi, fdata in sorted(floors.items()):
        print(f"\n  --- {fi + 1}층 ---")
        visualize_floor(fi, fdata, peaks)

    print(f"\n  Done! Output: {OUTPUT_DIR}")


if __name__ == '__main__':
    main()
