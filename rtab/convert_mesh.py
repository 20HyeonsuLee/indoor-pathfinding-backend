import open3d as o3d

# 합쳐진 point cloud 로드
pcd = o3d.io.read_point_cloud("merged_cloud.ply")

# Normal 계산 (mesh 생성에 필요)
pcd.estimate_normals(search_param=o3d.geometry.KDTreeSearchParamHybrid(radius=0.1, max_nn=30))

# Poisson mesh 생성
mesh, densities = o3d.geometry.TriangleMesh.create_from_point_cloud_poisson(pcd, depth=9)

# 저장
o3d.io.write_triangle_mesh("merged_mesh.ply", mesh)
print("Saved merged_mesh.ply")
