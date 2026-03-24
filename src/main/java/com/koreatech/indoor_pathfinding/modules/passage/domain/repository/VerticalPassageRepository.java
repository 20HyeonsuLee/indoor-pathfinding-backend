package com.koreatech.indoor_pathfinding.modules.passage.domain.repository;

import com.koreatech.indoor_pathfinding.modules.passage.domain.model.PassageType;
import com.koreatech.indoor_pathfinding.modules.passage.domain.model.VerticalPassage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VerticalPassageRepository extends JpaRepository<VerticalPassage, UUID> {

    List<VerticalPassage> findByBuildingId(UUID buildingId);

    List<VerticalPassage> findByBuildingIdAndType(UUID buildingId, PassageType type);

    @Query("SELECT vp FROM VerticalPassage vp LEFT JOIN FETCH vp.segments WHERE vp.id = :id")
    Optional<VerticalPassage> findByIdWithSegments(@Param("id") UUID id);

    void deleteByBuildingId(UUID buildingId);
}
