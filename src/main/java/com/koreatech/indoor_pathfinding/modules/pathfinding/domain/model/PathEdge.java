package com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model;

import com.koreatech.indoor_pathfinding.modules.pathprocessing.domain.model.PathSegment;
import com.koreatech.indoor_pathfinding.shared.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "path_edges", indexes = {
    @Index(name = "idx_path_edges_from_node", columnList = "from_node_id"),
    @Index(name = "idx_path_edges_to_node", columnList = "to_node_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class PathEdge extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_node_id", nullable = false)
    private PathNode fromNode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_node_id", nullable = false)
    private PathNode toNode;

    @Column(nullable = false)
    private Double distance;

    @Enumerated(EnumType.STRING)
    @Column(name = "edge_type", nullable = false)
    private EdgeType edgeType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "path_segment_id")
    private PathSegment pathSegment;

    @Column(name = "is_bidirectional", nullable = false)
    @Builder.Default
    private Boolean isBidirectional = true;

    public double getWeightedDistance(PathPreference preference) {
        return switch (preference) {
            case SHORTEST -> distance;
            case ELEVATOR_FIRST -> switch (edgeType) {
                case HORIZONTAL -> distance;
                case VERTICAL_ELEVATOR -> distance * 0.5;
                case VERTICAL_STAIRCASE -> distance * 2.0;
            };
            case STAIRCASE_FIRST -> switch (edgeType) {
                case HORIZONTAL -> distance;
                case VERTICAL_ELEVATOR -> distance * 2.0;
                case VERTICAL_STAIRCASE -> distance * 0.8;
            };
        };
    }
}
