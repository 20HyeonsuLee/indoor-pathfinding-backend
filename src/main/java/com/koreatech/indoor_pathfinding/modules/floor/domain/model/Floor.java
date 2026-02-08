package com.koreatech.indoor_pathfinding.modules.floor.domain.model;

import com.koreatech.indoor_pathfinding.modules.building.domain.model.Building;
import com.koreatech.indoor_pathfinding.shared.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "floors", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"building_id", "level"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Floor extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "building_id", nullable = false)
    private Building building;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int level;

    @Column
    private Double height;

    @OneToOne(mappedBy = "floor", cascade = CascadeType.ALL, orphanRemoval = true)
    private FloorPath floorPath;

    public void updateBuilding(Building building) {
        this.building = building;
    }

    public void updateFloorPath(FloorPath floorPath) {
        this.floorPath = floorPath;
        if (floorPath != null) {
            floorPath.setFloor(this);
        }
    }

    public void updateInfo(String name, Double height) {
        if (name != null) this.name = name;
        if (height != null) this.height = height;
    }
}
