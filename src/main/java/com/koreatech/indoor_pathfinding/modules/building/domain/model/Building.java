package com.koreatech.indoor_pathfinding.modules.building.domain.model;

import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;
import com.koreatech.indoor_pathfinding.modules.passage.domain.model.VerticalPassage;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ScanSession;
import com.koreatech.indoor_pathfinding.shared.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.locationtech.jts.geom.Point;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "buildings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Building extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(columnDefinition = "geometry(Point,4326)")
    private Point location;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private BuildingStatus status = BuildingStatus.DRAFT;

    @OneToMany(mappedBy = "building", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("level ASC")
    @Builder.Default
    private Set<Floor> floors = new HashSet<>();

    @OneToMany(mappedBy = "building", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private Set<VerticalPassage> verticalPassages = new HashSet<>();

    @OneToMany(mappedBy = "building", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ScanSession> scanSessions = new ArrayList<>();

    public void updateStatus(BuildingStatus status) {
        this.status = status;
    }

    public void updateInfo(String name, String description) {
        if (name != null) this.name = name;
        if (description != null) this.description = description;
    }

    public void updateLocation(Point location) {
        this.location = location;
    }

    public void addFloor(Floor floor) {
        floors.add(floor);
        floor.updateBuilding(this);
    }

    public void addVerticalPassage(VerticalPassage passage) {
        verticalPassages.add(passage);
        passage.setBuilding(this);
    }

    public void addScanSession(ScanSession scanSession) {
        scanSessions.add(scanSession);
        scanSession.setBuilding(this);
    }
}
