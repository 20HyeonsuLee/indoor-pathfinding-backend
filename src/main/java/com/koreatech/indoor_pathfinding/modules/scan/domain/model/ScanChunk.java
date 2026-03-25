package com.koreatech.indoor_pathfinding.modules.scan.domain.model;

import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;
import com.koreatech.indoor_pathfinding.shared.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "scan_chunks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ScanChunk extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "floor_id", nullable = false)
    @Setter
    private Floor floor;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false)
    private String filePath;

    @Column(nullable = false)
    private Long fileSize;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ChunkStatus status = ChunkStatus.UPLOADED;

    @Column
    @Builder.Default
    private boolean active = true;

    @Column(nullable = false)
    private int uploadOrder;

    @Column(length = 2000)
    private String errorMessage;

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }

    public void markFailed(final String errorMessage) {
        this.status = ChunkStatus.FAILED;
        this.errorMessage = errorMessage;
    }
}
