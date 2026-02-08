"""
Path Visualization Service
Generates preview images for path processing results
"""

import matplotlib
matplotlib.use('Agg')
import numpy as np
import matplotlib.pyplot as plt
from typing import Dict, List, Tuple
import os


def _split_at_gaps(positions: np.ndarray, max_gap: float = 5.0) -> List[np.ndarray]:
    """
    연속 포인트 간 거리가 max_gap을 초과하면 별도 세그먼트로 분리합니다.
    이렇게 하면 왕복 제거 등으로 생긴 공백에서 대각선이 그려지지 않습니다.
    """
    if len(positions) < 2:
        return [positions]

    dists = np.linalg.norm(np.diff(positions, axis=0), axis=1)
    split_indices = np.where(dists > max_gap)[0] + 1  # 갭 다음 인덱스

    if len(split_indices) == 0:
        return [positions]

    segments = []
    prev = 0
    for idx in split_indices:
        if idx - prev >= 2:
            segments.append(positions[prev:idx])
        prev = idx
    if len(positions) - prev >= 2:
        segments.append(positions[prev:])

    return segments


def generate_preview_images(raw_positions: np.ndarray,
                            smoothed_floors: Dict[int, np.ndarray],
                            vertical_passages: List[dict],
                            output_prefix: str) -> dict:
    """
    Generate comparison preview images

    Args:
        raw_positions: Original trajectory positions
        smoothed_floors: Processed floor paths
        vertical_passages: Detected vertical passages
        output_prefix: Output file path prefix

    Returns:
        Dictionary with image paths
    """
    paths = {}

    # Generate raw trajectory image
    raw_path = f"{output_prefix}_raw.png"
    generate_raw_trajectory_image(raw_positions, raw_path)
    paths['raw'] = raw_path

    # Generate processed trajectory image
    processed_path = f"{output_prefix}_processed.png"
    generate_processed_trajectory_image(
        smoothed_floors, vertical_passages, processed_path
    )
    paths['processed'] = processed_path

    # Generate comparison image
    comparison_path = f"{output_prefix}_comparison.png"
    generate_comparison_image(
        raw_positions, smoothed_floors, vertical_passages, comparison_path
    )
    paths['comparison'] = comparison_path

    return paths


def generate_raw_trajectory_image(positions: np.ndarray, output_path: str):
    """Generate image of raw trajectory"""
    x, y, z = positions[:, 0], positions[:, 1], positions[:, 2]

    fig = plt.figure(figsize=(16, 12))

    # 1. 3D view
    ax1 = fig.add_subplot(221, projection='3d')
    colors = np.linspace(0, 1, len(x))
    ax1.scatter(x, y, z, c=colors, cmap='viridis', s=10, alpha=0.8)
    ax1.plot(x, y, z, 'k-', linewidth=0.3, alpha=0.3)
    ax1.scatter(x[0], y[0], z[0], c='lime', s=150, marker='o',
                label='Start', edgecolors='black', linewidths=2, zorder=10)
    ax1.scatter(x[-1], y[-1], z[-1], c='red', s=150, marker='X',
                label='End', edgecolors='black', linewidths=2, zorder=10)
    ax1.set_xlabel('X (m)')
    ax1.set_ylabel('Y (m)')
    ax1.set_zlabel('Z (m)')
    ax1.set_title('3D Raw Trajectory', fontsize=12, fontweight='bold')
    ax1.legend()

    # 2. Top view (X-Y)
    ax2 = fig.add_subplot(222)
    ax2.scatter(x, y, c=colors, cmap='viridis', s=10, alpha=0.8)
    ax2.plot(x, y, 'k-', linewidth=0.3, alpha=0.3)
    ax2.scatter(x[0], y[0], c='lime', s=150, marker='o',
                label='Start', edgecolors='black', linewidths=2, zorder=10)
    ax2.scatter(x[-1], y[-1], c='red', s=150, marker='X',
                label='End', edgecolors='black', linewidths=2, zorder=10)
    ax2.set_xlabel('X (m)')
    ax2.set_ylabel('Y (m)')
    ax2.set_title('Top View (X-Y)', fontsize=12, fontweight='bold')
    ax2.legend()
    ax2.grid(True, alpha=0.3)
    ax2.axis('equal')

    # 3. Side view (X-Z)
    ax3 = fig.add_subplot(223)
    ax3.scatter(x, z, c=colors, cmap='viridis', s=10, alpha=0.8)
    ax3.plot(x, z, 'k-', linewidth=0.3, alpha=0.3)
    ax3.scatter(x[0], z[0], c='lime', s=150, marker='o',
                label='Start', edgecolors='black', linewidths=2, zorder=10)
    ax3.scatter(x[-1], z[-1], c='red', s=150, marker='X',
                label='End', edgecolors='black', linewidths=2, zorder=10)
    ax3.set_xlabel('X (m)')
    ax3.set_ylabel('Z (m)')
    ax3.set_title('Side View (X-Z)', fontsize=12, fontweight='bold')
    ax3.legend()
    ax3.grid(True, alpha=0.3)

    # 4. Height profile
    ax4 = fig.add_subplot(224)
    ax4.plot(range(len(z)), z, 'b-', linewidth=1)
    ax4.fill_between(range(len(z)), z, alpha=0.3)
    ax4.set_xlabel('Point Index')
    ax4.set_ylabel('Z (m)')
    ax4.set_title('Height Profile', fontsize=12, fontweight='bold')
    ax4.grid(True, alpha=0.3)

    # Calculate stats
    dist = np.sqrt(np.diff(x)**2 + np.diff(y)**2 + np.diff(z)**2)
    total_dist = np.sum(dist)

    plt.suptitle(f'Raw Camera Trajectory\nNodes: {len(positions)} | Distance: {total_dist:.1f}m',
                 fontsize=14, fontweight='bold')
    plt.tight_layout(rect=[0, 0, 1, 0.95])
    plt.savefig(output_path, dpi=150, bbox_inches='tight')
    plt.close()


def generate_processed_trajectory_image(smoothed_floors: Dict[int, np.ndarray],
                                         vertical_passages: List[dict],
                                         output_path: str):
    """Generate image of processed trajectory"""
    fig = plt.figure(figsize=(16, 12))

    # Define colors for floors
    floor_colors = plt.cm.Set1(np.linspace(0, 1, max(len(smoothed_floors), 3)))

    # 1. 3D view with floors
    ax1 = fig.add_subplot(221, projection='3d')

    for i, (floor_level, positions) in enumerate(sorted(smoothed_floors.items())):
        positions = np.array(positions)
        color = floor_colors[i % len(floor_colors)]
        floor_name = f"{floor_level}F" if floor_level >= 0 else f"B{abs(floor_level)}"
        segments = _split_at_gaps(positions)
        for j, seg in enumerate(segments):
            x, y, z = seg[:, 0], seg[:, 1], seg[:, 2]
            label = floor_name if j == 0 else None
            ax1.plot(x, y, z, '-', color=color, linewidth=2, label=label)
            ax1.scatter(x, y, z, c=[color], s=5, alpha=0.5)

    # Plot vertical passages
    for passage in vertical_passages:
        positions = np.array(passage['positions'])
        x, y, z = positions[:, 0], positions[:, 1], positions[:, 2]
        color = 'orange' if passage['type'] == 'STAIRCASE' else 'purple'
        linestyle = '--' if passage['type'] == 'STAIRCASE' else ':'
        ax1.plot(x, y, z, linestyle, color=color, linewidth=3, alpha=0.7)

    ax1.set_xlabel('X (m)')
    ax1.set_ylabel('Y (m)')
    ax1.set_zlabel('Z (m)')
    ax1.set_title('3D Processed Trajectory', fontsize=12, fontweight='bold')
    ax1.legend(loc='upper left')

    # 2. Floor plans (top view for each floor)
    ax2 = fig.add_subplot(222)

    for i, (floor_level, positions) in enumerate(sorted(smoothed_floors.items())):
        positions = np.array(positions)
        color = floor_colors[i % len(floor_colors)]
        floor_name = f"{floor_level}F" if floor_level >= 0 else f"B{abs(floor_level)}"
        segments = _split_at_gaps(positions)
        for j, seg in enumerate(segments):
            x, y = seg[:, 0], seg[:, 1]
            label = floor_name if j == 0 else None
            ax2.plot(x, y, '-', color=color, linewidth=2, label=label, alpha=0.7)

    ax2.set_xlabel('X (m)')
    ax2.set_ylabel('Y (m)')
    ax2.set_title('Floor Plans (Top View)', fontsize=12, fontweight='bold')
    ax2.legend()
    ax2.grid(True, alpha=0.3)
    ax2.axis('equal')

    # 3. Vertical passages detail
    ax3 = fig.add_subplot(223)

    passage_labels = []
    for passage in vertical_passages:
        positions = np.array(passage['positions'])
        z = positions[:, 2]
        color = 'orange' if passage['type'] == 'STAIRCASE' else 'purple'
        label = f"{passage['type']}: {passage['from_floor']}F→{passage['to_floor']}F"
        ax3.plot(range(len(z)), z, '-', color=color, linewidth=2, label=label)

    ax3.set_xlabel('Point Index')
    ax3.set_ylabel('Z (m)')
    ax3.set_title('Vertical Passages', fontsize=12, fontweight='bold')
    if vertical_passages:
        ax3.legend()
    ax3.grid(True, alpha=0.3)

    # 4. Statistics summary
    ax4 = fig.add_subplot(224)
    ax4.axis('off')

    total_distance = 0
    stats_text = "Processing Summary\n" + "=" * 30 + "\n\n"

    stats_text += f"Floors detected: {len(smoothed_floors)}\n"
    for floor_level, positions in sorted(smoothed_floors.items()):
        positions = np.array(positions)
        dist = np.sum(np.sqrt(np.sum(np.diff(positions, axis=0)**2, axis=1)))
        total_distance += dist
        floor_name = f"{floor_level}F" if floor_level >= 0 else f"B{abs(floor_level)}"
        stats_text += f"  {floor_name}: {len(positions)} pts, {dist:.1f}m\n"

    stats_text += f"\nVertical passages: {len(vertical_passages)}\n"
    stairs = sum(1 for p in vertical_passages if p['type'] == 'STAIRCASE')
    elevators = sum(1 for p in vertical_passages if p['type'] == 'ELEVATOR')
    stats_text += f"  Staircases: {stairs}\n"
    stats_text += f"  Elevators: {elevators}\n"

    stats_text += f"\nTotal distance: {total_distance:.1f}m"

    ax4.text(0.1, 0.9, stats_text, transform=ax4.transAxes,
             fontsize=11, verticalalignment='top', fontfamily='monospace',
             bbox=dict(boxstyle='round', facecolor='lightgray', alpha=0.8))

    plt.suptitle('Processed Trajectory Analysis', fontsize=14, fontweight='bold')
    plt.tight_layout(rect=[0, 0, 1, 0.95])
    plt.savefig(output_path, dpi=150, bbox_inches='tight')
    plt.close()


def generate_comparison_image(raw_positions: np.ndarray,
                               smoothed_floors: Dict[int, np.ndarray],
                               vertical_passages: List[dict],
                               output_path: str):
    """Generate side-by-side comparison image"""
    fig = plt.figure(figsize=(16, 8))

    # Raw trajectory (left)
    ax1 = fig.add_subplot(121)
    x, y = raw_positions[:, 0], raw_positions[:, 1]
    colors = np.linspace(0, 1, len(x))
    ax1.scatter(x, y, c=colors, cmap='viridis', s=5, alpha=0.5)
    ax1.plot(x, y, 'k-', linewidth=0.3, alpha=0.3)
    ax1.set_xlabel('X (m)')
    ax1.set_ylabel('Y (m)')
    ax1.set_title('Before Processing (Raw)', fontsize=12, fontweight='bold')
    ax1.grid(True, alpha=0.3)
    ax1.axis('equal')

    # Processed trajectory (right)
    ax2 = fig.add_subplot(122)
    floor_colors = plt.cm.Set1(np.linspace(0, 1, max(len(smoothed_floors), 3)))

    for i, (floor_level, positions) in enumerate(sorted(smoothed_floors.items())):
        positions = np.array(positions)
        color = floor_colors[i % len(floor_colors)]
        floor_name = f"{floor_level}F" if floor_level >= 0 else f"B{abs(floor_level)}"
        segments = _split_at_gaps(positions)
        for j, seg in enumerate(segments):
            x, y = seg[:, 0], seg[:, 1]
            label = floor_name if j == 0 else None
            ax2.plot(x, y, '-', color=color, linewidth=2, label=label)

    ax2.set_xlabel('X (m)')
    ax2.set_ylabel('Y (m)')
    ax2.set_title('After Processing (Smoothed)', fontsize=12, fontweight='bold')
    ax2.legend()
    ax2.grid(True, alpha=0.3)
    ax2.axis('equal')

    plt.suptitle('Path Processing Comparison', fontsize=14, fontweight='bold')
    plt.tight_layout(rect=[0, 0, 1, 0.95])
    plt.savefig(output_path, dpi=150, bbox_inches='tight')
    plt.close()
