package com.koreatech.indoor_pathfinding.modules.passage.domain.model;

import com.koreatech.indoor_pathfinding.modules.building.domain.model.Building;
import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.domain.model.PathSegment;
import com.koreatech.indoor_pathfinding.modules.pathprocessing.domain.model.Point3D;
import com.koreatech.indoor_pathfinding.shared.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.locationtech.jts.geom.LineString;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "vertical_passages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class VerticalPassage extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "building_id", nullable = false)
    @Setter
    private Building building;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PassageType type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_floor_id", nullable = false)
    private Floor fromFloor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_floor_id", nullable = false)
    private Floor toFloor;

    @Column(columnDefinition = "geometry(LineStringZ,0)")
    private LineString pathGeometry;

    @OneToMany(mappedBy = "verticalPassage", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sequenceOrder ASC")
    @Builder.Default
    private List<PathSegment> segments = new ArrayList<>();

    @Column
    private Double entryX;

    @Column
    private Double entryY;

    @Column
    private Double entryZ;

    @Column
    private Double exitX;

    @Column
    private Double exitY;

    @Column
    private Double exitZ;

    public void updateEntryPoint(double x, double y, double z) {
        this.entryX = x;
        this.entryY = y;
        this.entryZ = z;
    }

    public void updateExitPoint(double x, double y, double z) {
        this.exitX = x;
        this.exitY = y;
        this.exitZ = z;
    }

    public void addSegment(PathSegment segment) {
        segments.add(segment);
        segment.setVerticalPassage(this);
    }

    public void clearSegments() {
        segments.clear();
    }

    public Point3D getEntryPoint() {
        if (entryX == null || entryY == null || entryZ == null) {
            return null;
        }
        return Point3D.builder()
            .x(entryX)
            .y(entryY)
            .z(entryZ)
            .build();
    }

    public Point3D getExitPoint() {
        if (exitX == null || exitY == null || exitZ == null) {
            return null;
        }
        return Point3D.builder()
            .x(exitX)
            .y(exitY)
            .z(exitZ)
            .build();
    }
}
