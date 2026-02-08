package com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model;

import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;
import com.koreatech.indoor_pathfinding.modules.passage.domain.model.VerticalPassage;
import com.koreatech.indoor_pathfinding.shared.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "path_nodes", indexes = {
    @Index(name = "idx_path_nodes_floor", columnList = "floor_id"),
    @Index(name = "idx_path_nodes_poi_name", columnList = "poi_name"),
    @Index(name = "idx_path_nodes_coordinates", columnList = "x, y, z")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class PathNode extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "floor_id", nullable = false)
    private Floor floor;

    @Column(nullable = false)
    private Double x;

    @Column(nullable = false)
    private Double y;

    @Column(nullable = false)
    private Double z;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NodeType type;

    @Column(name = "poi_name")
    private String poiName;

    @Enumerated(EnumType.STRING)
    @Column(name = "poi_category")
    private PoiCategory poiCategory;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vertical_passage_id")
    private VerticalPassage verticalPassage;

    @Column(name = "is_passage_entry", nullable = false)
    @Builder.Default
    private Boolean isPassageEntry = false;

    public void updatePoi(String poiName, PoiCategory poiCategory) {
        this.poiName = poiName;
        this.poiCategory = poiCategory;
        if (poiName != null && !poiName.isBlank()) {
            this.type = NodeType.POI;
        }
    }

    public void clearPoi() {
        this.poiName = null;
        this.poiCategory = null;
        if (this.type == NodeType.POI) {
            this.type = NodeType.WAYPOINT;
        }
    }

    public double distanceTo(PathNode other) {
        double dx = this.x - other.x;
        double dy = this.y - other.y;
        double dz = this.z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public double distanceTo(double x, double y, double z) {
        double dx = this.x - x;
        double dy = this.y - y;
        double dz = this.z - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
