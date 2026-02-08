package com.koreatech.indoor_pathfinding.modules.floor.domain.repository;

import com.koreatech.indoor_pathfinding.modules.floor.domain.model.FloorPath;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface FloorPathRepository extends JpaRepository<FloorPath, UUID> {

    Optional<FloorPath> findByFloorId(UUID floorId);

    @Query("SELECT fp FROM FloorPath fp LEFT JOIN FETCH fp.segments WHERE fp.floor.id = :floorId")
    Optional<FloorPath> findByFloorIdWithSegments(@Param("floorId") UUID floorId);

    void deleteByFloorId(UUID floorId);
}
