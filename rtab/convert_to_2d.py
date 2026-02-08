import trimesh
import numpy as np
import matplotlib.pyplot as plt
import json

mesh = trimesh.load('merged_mesh.ply')  # 경로 맞춰줘

# 여러 높이 테스트 (최적 높이 찾기)
heights = [0.5, 0.8, 1.0, 1.2]

fig, axes = plt.subplots(2, 2, figsize=(14, 14))
axes = axes.flatten()

for i, z in enumerate(heights):
    slice_2d = mesh.section(plane_origin=[0, 0, z], plane_normal=[0, 0, 1])
    if slice_2d:
        slice_planar, _ = slice_2d.to_planar()
        for entity in slice_planar.entities:
            points = slice_planar.vertices[entity.points]
            axes[i].plot(points[:, 0], points[:, 1], 'k-', linewidth=1.5)
    axes[i].set_aspect('equal')
    axes[i].set_title(f'z = {z}m')
    axes[i].grid(True, alpha=0.3)

plt.tight_layout()
plt.savefig('height_comparison.png', dpi=150)
plt.show()
