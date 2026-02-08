package com.koreatech.indoor_pathfinding.modules.floor.domain.model;

import com.koreatech.indoor_pathfinding.modules.pathprocessing.domain.model.PathSegment;
import com.koreatech.indoor_pathfinding.shared.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.locationtech.jts.geom.LineString;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "floor_paths")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class FloorPath extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "floor_id", nullable = false)
    @Setter(AccessLevel.PACKAGE)
    private Floor floor;

    @Column(columnDefinition = "geometry(LineStringZ,0)")
    private LineString pathGeometry;

    @OneToMany(mappedBy = "floorPath", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sequenceOrder ASC")
    @Builder.Default
    private List<PathSegment> segments = new ArrayList<>();

    @Column
    private Double minX;

    @Column
    private Double maxX;

    @Column
    private Double minY;

    @Column
    private Double maxY;

    @Column
    private Double totalDistance;

    public void updateBounds(double minX, double maxX, double minY, double maxY) {
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
    }

    public void updateTotalDistance(double totalDistance) {
        this.totalDistance = totalDistance;
    }

    public void addSegment(PathSegment segment) {
        segments.add(segment);
        segment.setFloorPath(this);
    }

    public void clearSegments() {
        segments.clear();
    }
}
