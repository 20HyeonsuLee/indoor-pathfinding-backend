package com.koreatech.indoor_pathfinding.modules.floor.domain.model;

import com.koreatech.indoor_pathfinding.modules.building.domain.model.Building;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.MergedScan;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ScanChunk;
import com.koreatech.indoor_pathfinding.shared.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

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

    @Column(name = "ply_file_id")
    private String plyFileId;

    @OneToOne(mappedBy = "floor", cascade = CascadeType.ALL, orphanRemoval = true)
    private FloorPath floorPath;

    @OneToMany(mappedBy = "floor", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("uploadOrder ASC")
    @Builder.Default
    private List<ScanChunk> scanChunks = new ArrayList<>();

    @OneToOne(mappedBy = "floor", cascade = CascadeType.ALL, orphanRemoval = true)
    private MergedScan mergedScan;

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

    public void updatePlyFileId(String plyFileId) {
        this.plyFileId = plyFileId;
    }

    public void addScanChunk(ScanChunk chunk) {
        scanChunks.add(chunk);
        chunk.setFloor(this);
    }

    public List<ScanChunk> getActiveChunks() {
        return scanChunks.stream()
            .filter(ScanChunk::isActive)
            .toList();
    }

    public int nextUploadOrder() {
        return scanChunks.stream()
            .mapToInt(ScanChunk::getUploadOrder)
            .max()
            .orElse(0) + 1;
    }

    public void updateMergedScan(MergedScan mergedScan) {
        this.mergedScan = mergedScan;
        if (mergedScan != null) {
            mergedScan.setFloor(this);
        }
    }
}
