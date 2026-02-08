import open3d as o3d

pc1 = o3d.io.read_point_cloud("db/scan1_cloud.ply")
pc2 = o3d.io.read_point_cloud("db/scan2_cloud.ply")

# ICP로 정합
threshold = 0.5
reg = o3d.pipelines.registration.registration_icp(
    pc2, pc1, threshold,
    estimation_method=o3d.pipelines.registration.TransformationEstimationPointToPoint()
)

# 변환 적용 후 합치기
pc2.transform(reg.transformation)
merged = pc1 + pc2

o3d.io.write_point_cloud("merged_cloud.ply", merged)
