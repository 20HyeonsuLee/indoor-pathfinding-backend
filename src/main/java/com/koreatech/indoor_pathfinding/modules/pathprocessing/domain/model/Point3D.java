package com.koreatech.indoor_pathfinding.modules.pathprocessing.domain.model;

import jakarta.persistence.Embeddable;
import lombok.*;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Point3D {

    private Double x;
    private Double y;
    private Double z;

    public double distanceTo(Point3D other) {
        double dx = this.x - other.x;
        double dy = this.y - other.y;
        double dz = this.z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public double distanceXY(Point3D other) {
        double dx = this.x - other.x;
        double dy = this.y - other.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    public double distanceZ(Point3D other) {
        return Math.abs(this.z - other.z);
    }

    @Override
    public String toString() {
        return String.format("(%.4f, %.4f, %.4f)", x, y, z);
    }
}
