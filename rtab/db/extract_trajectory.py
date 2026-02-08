import sqlite3
import numpy as np
import struct
import matplotlib.pyplot as plt
from mpl_toolkits.mplot3d import Axes3D
import sys

def extract_pose(pose_blob):
    """Extract 3x4 transformation matrix from blob (48 bytes = 12 floats)"""
    if pose_blob is None or len(pose_blob) == 0:
        return None
    # 12 floats = 3x4 matrix (row-major)
    values = struct.unpack('12f', pose_blob)
    matrix = np.array(values).reshape(3, 4)
    return matrix

def get_position(matrix):
    """Extract position (x, y, z) from transformation matrix"""
    if matrix is None:
        return None
    return matrix[:, 3]  # Last column is translation

def visualize_trajectory(db_path, output_prefix):
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()

    # Get all poses
    cursor.execute('SELECT id, pose FROM Node ORDER BY id')
    rows = cursor.fetchall()

    positions = []
    node_ids = []

    for node_id, pose_blob in rows:
        matrix = extract_pose(pose_blob)
        if matrix is not None:
            pos = get_position(matrix)
            # Check if pose is valid (not identity or zero)
            if not np.allclose(pos, [0, 0, 0]):
                positions.append(pos)
                node_ids.append(node_id)

    conn.close()

    if len(positions) == 0:
        print("No valid poses found!")
        return

    positions = np.array(positions)
    x, y, z = positions[:, 0], positions[:, 1], positions[:, 2]

    print(f"=== {db_path.split('/')[-1]} ===")
    print(f"Total nodes: {len(positions)}")
    print(f"X range: {x.min():.3f} ~ {x.max():.3f} m")
    print(f"Y range: {y.min():.3f} ~ {y.max():.3f} m")
    print(f"Z range: {z.min():.3f} ~ {z.max():.3f} m")

    # Calculate total distance
    dist = np.sqrt(np.diff(x)**2 + np.diff(y)**2 + np.diff(z)**2)
    total_dist = np.sum(dist)
    print(f"Total distance: {total_dist:.2f} m")

    # Create 3D visualization
    fig = plt.figure(figsize=(16, 12))

    # 1. 3D view
    ax1 = fig.add_subplot(221, projection='3d')
    colors = np.linspace(0, 1, len(x))
    scatter = ax1.scatter(x, y, z, c=colors, cmap='viridis', s=30, alpha=0.8)
    ax1.plot(x, y, z, 'k-', linewidth=0.5, alpha=0.3)
    ax1.scatter(x[0], y[0], z[0], c='lime', s=200, marker='o', label='Start', edgecolors='black', linewidths=2, zorder=10)
    ax1.scatter(x[-1], y[-1], z[-1], c='red', s=200, marker='X', label='End', edgecolors='black', linewidths=2, zorder=10)
    ax1.set_xlabel('X (m)')
    ax1.set_ylabel('Y (m)')
    ax1.set_zlabel('Z (m)')
    ax1.set_title('3D Camera Trajectory', fontsize=14, fontweight='bold')
    ax1.legend()

    # 2. Top view (X-Y)
    ax2 = fig.add_subplot(222)
    ax2.scatter(x, y, c=colors, cmap='viridis', s=30, alpha=0.8)
    ax2.plot(x, y, 'k-', linewidth=0.5, alpha=0.3)
    ax2.scatter(x[0], y[0], c='lime', s=200, marker='o', label='Start', edgecolors='black', linewidths=2, zorder=10)
    ax2.scatter(x[-1], y[-1], c='red', s=200, marker='X', label='End', edgecolors='black', linewidths=2, zorder=10)
    ax2.set_xlabel('X (m)')
    ax2.set_ylabel('Y (m)')
    ax2.set_title('Top View (X-Y)', fontsize=14, fontweight='bold')
    ax2.legend()
    ax2.grid(True, alpha=0.3)
    ax2.axis('equal')

    # 3. Side view (X-Z)
    ax3 = fig.add_subplot(223)
    ax3.scatter(x, z, c=colors, cmap='viridis', s=30, alpha=0.8)
    ax3.plot(x, z, 'k-', linewidth=0.5, alpha=0.3)
    ax3.scatter(x[0], z[0], c='lime', s=200, marker='o', label='Start', edgecolors='black', linewidths=2, zorder=10)
    ax3.scatter(x[-1], z[-1], c='red', s=200, marker='X', label='End', edgecolors='black', linewidths=2, zorder=10)
    ax3.set_xlabel('X (m)')
    ax3.set_ylabel('Z (m)')
    ax3.set_title('Side View (X-Z)', fontsize=14, fontweight='bold')
    ax3.legend()
    ax3.grid(True, alpha=0.3)
    ax3.axis('equal')

    # 4. Front view (Y-Z)
    ax4 = fig.add_subplot(224)
    ax4.scatter(y, z, c=colors, cmap='viridis', s=30, alpha=0.8)
    ax4.plot(y, z, 'k-', linewidth=0.5, alpha=0.3)
    ax4.scatter(y[0], z[0], c='lime', s=200, marker='o', label='Start', edgecolors='black', linewidths=2, zorder=10)
    ax4.scatter(y[-1], z[-1], c='red', s=200, marker='X', label='End', edgecolors='black', linewidths=2, zorder=10)
    ax4.set_xlabel('Y (m)')
    ax4.set_ylabel('Z (m)')
    ax4.set_title('Front View (Y-Z)', fontsize=14, fontweight='bold')
    ax4.legend()
    ax4.grid(True, alpha=0.3)
    ax4.axis('equal')

    plt.suptitle(f'RTAB-Map Camera Trajectory\n{db_path.split("/")[-1]} | Distance: {total_dist:.1f}m | Nodes: {len(positions)}',
                 fontsize=16, fontweight='bold')
    plt.tight_layout(rect=[0, 0, 1, 0.95])
    plt.savefig(f'{output_prefix}_trajectory_3d.png', dpi=150, bbox_inches='tight')
    print(f'\nSaved {output_prefix}_trajectory_3d.png')

if __name__ == '__main__':
    db_files = ['scan1.db', 'scan2.db', 'test.db']

    for db_file in db_files:
        db_path = f'/Users/leehyeonsu/home/koreatech/graduate_project/indoor-backend/rtab/db/{db_file}'
        output_prefix = db_file.replace('.db', '')
        try:
            visualize_trajectory(db_path, output_prefix)
            print()
        except Exception as e:
            print(f"Error processing {db_file}: {e}")
            print()
