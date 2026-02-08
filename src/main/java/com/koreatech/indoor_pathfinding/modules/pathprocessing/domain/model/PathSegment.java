package com.koreatech.indoor_pathfinding.modules.pathprocessing.domain.model;

import com.koreatech.indoor_pathfinding.modules.floor.domain.model.FloorPath;
import com.koreatech.indoor_pathfinding.modules.passage.domain.model.VerticalPassage;
import com.koreatech.indoor_pathfinding.shared.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "path_segments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class PathSegment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "floor_path_id")
    @Setter
    private FloorPath floorPath;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vertical_passage_id")
    @Setter
    private VerticalPassage verticalPassage;

    @Column(nullable = false)
    private int sequenceOrder;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "x", column = @Column(name = "start_x")),
        @AttributeOverride(name = "y", column = @Column(name = "start_y")),
        @AttributeOverride(name = "z", column = @Column(name = "start_z"))
    })
    private Point3D startPoint;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "x", column = @Column(name = "end_x")),
        @AttributeOverride(name = "y", column = @Column(name = "end_y")),
        @AttributeOverride(name = "z", column = @Column(name = "end_z"))
    })
    private Point3D endPoint;

    @Column
    private Double length;

    @PrePersist
    @PreUpdate
    private void calculateLength() {
        if (startPoint != null && endPoint != null) {
            this.length = startPoint.distanceTo(endPoint);
        }
    }
}
