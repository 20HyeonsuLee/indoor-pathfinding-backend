package com.koreatech.indoor_pathfinding.modules.localization.infrastructure.persistence;

import com.koreatech.indoor_pathfinding.shared.exception.BusinessException;
import com.koreatech.indoor_pathfinding.shared.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
public class RtabMapImageExtractor {

    private static final int POSE_BLOB_SIZE = 48;
    private static final int FLOATS_PER_POSE = 12;
    private static final int SECTOR_COUNT = 3;
    private static final double SECTOR_SIZE = 360.0 / SECTOR_COUNT;

    public record NearbyNodeImage(
        int nodeId,
        double x,
        double y,
        double z,
        double distance,
        double cameraAngle,
        byte[] imageData
    ) {}

    public List<NearbyNodeImage> extractNearbyImages(
            final Path dbPath,
            final double targetX,
            final double targetY,
            final double targetZ,
            final int maxImages
    ) {
        validateDbFile(dbPath);

        final List<NodePose> nearbyNodes = findNearbyNodes(dbPath, targetX, targetY, targetZ);
        final List<NodePose> selected = selectDiverseDirections(nearbyNodes, maxImages);
        return loadImages(dbPath, selected);
    }

    private void validateDbFile(final Path dbPath) {
        if (!Files.exists(dbPath)) {
            throw new BusinessException(ErrorCode.ENTITY_NOT_FOUND,
                "RTAB-Map DB file not found: " + dbPath);
        }
    }

    private List<NodePose> findNearbyNodes(
            final Path dbPath,
            final double targetX,
            final double targetY,
            final double targetZ
    ) {
        final List<NodePose> nodes = new ArrayList<>();
        final String url = "jdbc:sqlite:" + dbPath.toAbsolutePath();

        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT id, pose FROM Node ORDER BY id")) {

            while (resultSet.next()) {
                final int nodeId = resultSet.getInt("id");
                final byte[] poseBlob = resultSet.getBytes("pose");

                parsePose(poseBlob).ifPresent(pose -> {
                    final double distance = calculateDistance(
                        pose.tx(), pose.ty(), pose.tz(),
                        targetX, targetY, targetZ
                    );
                    nodes.add(new NodePose(nodeId, pose.tx(), pose.ty(), pose.tz(), distance, pose.cameraAngle()));
                });
            }

        } catch (SQLException exception) {
            log.error("Failed to read RTAB-Map DB: {}", dbPath, exception);
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR,
                "Failed to read RTAB-Map DB: " + exception.getMessage());
        }

        nodes.sort(Comparator.comparingDouble(NodePose::distance));
        return nodes;
    }

    private List<NodePose> selectDiverseDirections(final List<NodePose> sortedNodes, final int maxImages) {
        if (sortedNodes.size() <= maxImages) {
            return sortedNodes;
        }

        final List<List<NodePose>> sectors = new ArrayList<>();
        for (int i = 0; i < SECTOR_COUNT; i++) {
            sectors.add(new ArrayList<>());
        }

        final int candidateCount = Math.min(sortedNodes.size(), 30);
        for (int i = 0; i < candidateCount; i++) {
            final NodePose node = sortedNodes.get(i);
            final int sectorIndex = (int) (node.cameraAngle() / SECTOR_SIZE) % SECTOR_COUNT;
            sectors.get(sectorIndex).add(node);
        }

        final List<NodePose> selected = new ArrayList<>();
        for (final List<NodePose> sector : sectors) {
            if (!sector.isEmpty() && selected.size() < maxImages) {
                selected.add(sector.getFirst());
            }
        }

        if (selected.size() < maxImages) {
            for (final NodePose node : sortedNodes) {
                if (selected.size() >= maxImages) {
                    break;
                }
                if (selected.stream().noneMatch(s -> s.nodeId() == node.nodeId())) {
                    selected.add(node);
                }
            }
        }

        return selected;
    }

    private List<NearbyNodeImage> loadImages(final Path dbPath, final List<NodePose> nodes) {
        final List<NearbyNodeImage> results = new ArrayList<>();
        final String url = "jdbc:sqlite:" + dbPath.toAbsolutePath();

        try (Connection connection = DriverManager.getConnection(url);
             PreparedStatement statement = connection.prepareStatement("SELECT image FROM Data WHERE id = ?")) {

            for (final NodePose node : nodes) {
                statement.setInt(1, node.nodeId());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        final byte[] imageData = resultSet.getBytes("image");
                        if (imageData != null && imageData.length > 0) {
                            results.add(new NearbyNodeImage(
                                node.nodeId(), node.x(), node.y(), node.z(),
                                node.distance(), node.cameraAngle(), imageData
                            ));
                        }
                    }
                }
            }

        } catch (SQLException exception) {
            log.error("Failed to load images from RTAB-Map DB: {}", dbPath, exception);
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR,
                "Failed to load images: " + exception.getMessage());
        }

        return results;
    }

    private java.util.Optional<ParsedPose> parsePose(final byte[] poseBlob) {
        if (poseBlob == null || poseBlob.length != POSE_BLOB_SIZE) {
            return java.util.Optional.empty();
        }

        final ByteBuffer buffer = ByteBuffer.wrap(poseBlob).order(ByteOrder.LITTLE_ENDIAN);
        final float[] values = new float[FLOATS_PER_POSE];
        for (int i = 0; i < FLOATS_PER_POSE; i++) {
            values[i] = buffer.getFloat();
        }

        // 3x4 row-major: [r11,r12,r13,tx, r21,r22,r23,ty, r31,r32,r33,tz]
        final double tx = values[3];
        final double ty = values[7];
        final double tz = values[11];

        if (isInvalidPosition(tx, ty, tz)) {
            return java.util.Optional.empty();
        }

        // Camera forward = 3rd column of rotation matrix
        final double forwardX = values[2];
        final double forwardZ = values[10];
        final double angle = (Math.toDegrees(Math.atan2(forwardX, forwardZ)) + 360) % 360;

        return java.util.Optional.of(new ParsedPose(tx, ty, tz, angle));
    }

    private boolean isInvalidPosition(final double x, final double y, final double z) {
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) {
            return true;
        }
        if (Double.isInfinite(x) || Double.isInfinite(y) || Double.isInfinite(z)) {
            return true;
        }
        return Math.abs(x) < 1e-6 && Math.abs(y) < 1e-6 && Math.abs(z) < 1e-6;
    }

    private double calculateDistance(
            final double x1, final double y1, final double z1,
            final double x2, final double y2, final double z2
    ) {
        final double dx = x1 - x2;
        final double dy = y1 - y2;
        final double dz = z1 - z2;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private record ParsedPose(double tx, double ty, double tz, double cameraAngle) {}

    private record NodePose(int nodeId, double x, double y, double z, double distance, double cameraAngle) {}
}
