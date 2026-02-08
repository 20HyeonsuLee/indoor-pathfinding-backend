package com.koreatech.indoor_pathfinding.modules.scan.domain.model;

import com.koreatech.indoor_pathfinding.modules.building.domain.model.Building;
import com.koreatech.indoor_pathfinding.shared.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "scan_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ScanSession extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "building_id", nullable = false)
    @Setter
    private Building building;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false)
    private String filePath;

    @Column
    private Long fileSize;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ScanStatus status = ScanStatus.UPLOADED;

    @Column(length = 2000)
    private String errorMessage;

    @Column
    private String previewImagePath;

    @Column
    private String processedPreviewPath;

    @Column
    private Integer totalNodes;

    @Column
    private Double totalDistance;

    public void updateStatus(ScanStatus status) {
        this.status = status;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
        this.status = ScanStatus.FAILED;
    }

    public void updateProcessingResult(
            String previewImagePath,
            String processedPreviewPath,
            int totalNodes,
            double totalDistance
    ) {
        this.previewImagePath = previewImagePath;
        this.processedPreviewPath = processedPreviewPath;
        this.totalNodes = totalNodes;
        this.totalDistance = totalDistance;
        this.status = ScanStatus.COMPLETED;
    }
}
