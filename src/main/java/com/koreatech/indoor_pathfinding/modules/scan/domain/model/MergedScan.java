package com.koreatech.indoor_pathfinding.modules.scan.domain.model;

import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;
import com.koreatech.indoor_pathfinding.shared.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "merged_scans")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class MergedScan extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "floor_id", nullable = false, unique = true)
    @Setter
    private Floor floor;

    @Column
    private String filePath;

    @Column(name = "ply_file_id")
    private String plyFileId;

    @Column(name = "vps_map_id")
    private String vpsMapId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private MergedScanStatus status = MergedScanStatus.MERGING;

    @Column(columnDefinition = "TEXT")
    private String sourceChunkIds;

    @Column(length = 2000)
    private String errorMessage;

    @Column
    private Integer totalNodes;

    @Column
    private Double totalDistance;

    @Column
    private String previewImagePath;

    public void markMerged(final String filePath) {
        this.filePath = filePath;
        this.status = MergedScanStatus.MERGED;
    }

    public void markMergeFailed(final String errorMessage) {
        this.status = MergedScanStatus.MERGE_FAILED;
        this.errorMessage = errorMessage;
    }

    public void startProcessing() {
        this.status = MergedScanStatus.EXTRACTING;
    }

    public void markCompleted(final int totalNodes, final double totalDistance,
                              final String previewImagePath) {
        this.totalNodes = totalNodes;
        this.totalDistance = totalDistance;
        this.previewImagePath = previewImagePath;
        this.status = MergedScanStatus.COMPLETED;
    }

    public void markFailed(final String errorMessage) {
        this.status = MergedScanStatus.FAILED;
        this.errorMessage = errorMessage;
    }

    public void updatePlyFileId(final String plyFileId) {
        this.plyFileId = plyFileId;
    }

    public void updateVpsMapId(final String vpsMapId) {
        this.vpsMapId = vpsMapId;
    }

    public boolean isProcessable() {
        return this.status == MergedScanStatus.MERGED
            || this.status == MergedScanStatus.COMPLETED
            || this.status == MergedScanStatus.FAILED;
    }
}
