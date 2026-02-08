package com.koreatech.indoor_pathfinding.modules.pathprocessing.domain.repository;

import com.koreatech.indoor_pathfinding.modules.pathprocessing.domain.model.PathSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PathSegmentRepository extends JpaRepository<PathSegment, UUID> {

    List<PathSegment> findByFloorPathIdOrderBySequenceOrderAsc(UUID floorPathId);

    List<PathSegment> findByVerticalPassageIdOrderBySequenceOrderAsc(UUID verticalPassageId);

    void deleteByFloorPathId(UUID floorPathId);

    void deleteByVerticalPassageId(UUID verticalPassageId);
}
