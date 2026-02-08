import sqlite3
import numpy as np
import struct
import matplotlib.pyplot as plt
from mpl_toolkits.mplot3d import Axes3D

def extract_poses(db_path):
    """Extract all poses from RTAB-Map database"""
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()
    cursor.execute('SELECT id, pose FROM Node ORDER BY id')
    rows = cursor.fetchall()
    conn.close()

    positions = []
    for node_id, pose_blob in rows:
        if pose_blob and len(pose_blob) == 48:
            values = struct.unpack('12f', pose_blob)
            matrix = np.array(values).reshape(3, 4)
            pos = matrix[:, 3]
            if not np.allclose(pos, [0, 0, 0]):
                positions.append(pos)

    return np.array(positions) if positions else None

# Extract from both databases
scan1_poses = extract_poses('scan1.db')
scan2_poses = extract_poses('scan2.db')

print(f"scan1.db: {len(scan1_poses)} poses")
print(f"scan2.db: {len(scan2_poses)} poses")

# Visualize both trajectories together
fig = plt.figure(figsize=(16, 12))

# 3D view - both trajectories
ax1 = fig.add_subplot(221, projection='3d')

if scan1_poses is not None:
    x1, y1, z1 = scan1_poses[:, 0], scan1_poses[:, 1], scan1_poses[:, 2]
    ax1.plot(x1, y1, z1, 'b-', linewidth=2, alpha=0.8, label='scan1.db')
    ax1.scatter(x1[0], y1[0], z1[0], c='lime', s=200, marker='o', edgecolors='black', zorder=10)
    ax1.scatter(x1[-1], y1[-1], z1[-1], c='blue', s=150, marker='s', edgecolors='black', zorder=10)

if scan2_poses is not None:
    x2, y2, z2 = scan2_poses[:, 0], scan2_poses[:, 1], scan2_poses[:, 2]
    ax1.plot(x2, y2, z2, 'r-', linewidth=2, alpha=0.8, label='scan2.db')
    ax1.scatter(x2[0], y2[0], z2[0], c='orange', s=200, marker='o', edgecolors='black', zorder=10)
    ax1.scatter(x2[-1], y2[-1], z2[-1], c='red', s=150, marker='s', edgecolors='black', zorder=10)

ax1.set_xlabel('X (m)')
ax1.set_ylabel('Y (m)')
ax1.set_zlabel('Z (m)')
ax1.set_title('3D View - Both Trajectories', fontsize=14, fontweight='bold')
ax1.legend()

# Top view (X-Y)
ax2 = fig.add_subplot(222)
if scan1_poses is not None:
    ax2.plot(x1, y1, 'b-', linewidth=2, alpha=0.8, label='scan1.db')
    ax2.scatter(x1[0], y1[0], c='lime', s=150, marker='o', edgecolors='black', zorder=10)
if scan2_poses is not None:
    ax2.plot(x2, y2, 'r-', linewidth=2, alpha=0.8, label='scan2.db')
    ax2.scatter(x2[0], y2[0], c='orange', s=150, marker='o', edgecolors='black', zorder=10)
ax2.set_xlabel('X (m)')
ax2.set_ylabel('Y (m)')
ax2.set_title('Top View (X-Y) - Overlap Check', fontsize=14, fontweight='bold')
ax2.legend()
ax2.grid(True, alpha=0.3)
ax2.axis('equal')

# Side view (X-Z)
ax3 = fig.add_subplot(223)
if scan1_poses is not None:
    ax3.plot(x1, z1, 'b-', linewidth=2, alpha=0.8, label='scan1.db')
if scan2_poses is not None:
    ax3.plot(x2, z2, 'r-', linewidth=2, alpha=0.8, label='scan2.db')
ax3.set_xlabel('X (m)')
ax3.set_ylabel('Z (m)')
ax3.set_title('Side View (X-Z)', fontsize=14, fontweight='bold')
ax3.legend()
ax3.grid(True, alpha=0.3)
ax3.axis('equal')

# Info panel
ax4 = fig.add_subplot(224)
ax4.axis('off')

info_text = """
=== 분할 촬영 병합 가이드 ===

✅ 병합 가능 조건:
• 두 스캔이 겹치는 영역 필요
• 같은 특징점이 보여야 함

📍 현재 상태:
• scan1.db: {} poses, 범위 X({:.1f}~{:.1f}m)
• scan2.db: {} poses, 범위 X({:.1f}~{:.1f}m)

🔗 병합 방법:
1. RTAB-Map 앱: Merge databases
2. PC: rtabmap-reprocess 명령
3. rtabmap GUI: Database Viewer

⚠️ 주의사항:
• 겹치는 영역 최소 10-20% 필요
• 비슷한 조명 조건
• 움직이는 물체 없어야 함
""".format(
    len(scan1_poses) if scan1_poses is not None else 0,
    x1.min() if scan1_poses is not None else 0,
    x1.max() if scan1_poses is not None else 0,
    len(scan2_poses) if scan2_poses is not None else 0,
    x2.min() if scan2_poses is not None else 0,
    x2.max() if scan2_poses is not None else 0,
)
ax4.text(0.1, 0.9, info_text, transform=ax4.transAxes, fontsize=11,
         verticalalignment='top', fontfamily='monospace',
         bbox=dict(boxstyle='round', facecolor='wheat', alpha=0.5))

plt.suptitle('RTAB-Map 분할 촬영 비교\n(병합 전 두 경로 겹침 확인)', fontsize=16, fontweight='bold')
plt.tight_layout(rect=[0, 0, 1, 0.95])
plt.savefig('trajectories_overlap_check.png', dpi=150, bbox_inches='tight')
print('\nSaved trajectories_overlap_check.png')

# Check overlap
if scan1_poses is not None and scan2_poses is not None:
    # Simple overlap check based on bounding box
    x1_range = (x1.min(), x1.max())
    x2_range = (x2.min(), x2.max())
    y1_range = (y1.min(), y1.max())
    y2_range = (y2.min(), y2.max())

    x_overlap = max(0, min(x1_range[1], x2_range[1]) - max(x1_range[0], x2_range[0]))
    y_overlap = max(0, min(y1_range[1], y2_range[1]) - max(y1_range[0], y2_range[0]))

    print(f"\n=== 겹침 분석 ===")
    print(f"scan1 X 범위: {x1_range[0]:.2f} ~ {x1_range[1]:.2f} m")
    print(f"scan2 X 범위: {x2_range[0]:.2f} ~ {x2_range[1]:.2f} m")
    print(f"X 겹침: {x_overlap:.2f} m")
    print(f"Y 겹침: {y_overlap:.2f} m")

    if x_overlap > 0.5 or y_overlap > 0.5:
        print("✅ 병합 가능성 있음!")
    else:
        print("⚠️ 겹치는 영역이 부족할 수 있음")
