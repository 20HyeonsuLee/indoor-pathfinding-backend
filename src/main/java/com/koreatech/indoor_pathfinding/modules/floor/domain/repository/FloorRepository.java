package com.koreatech.indoor_pathfinding.modules.floor.domain.repository;

import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FloorRepository extends JpaRepository<Floor, UUID> {

    List<Floor> findByBuildingIdOrderByLevelAsc(UUID buildingId);

    Optional<Floor> findByBuildingIdAndLevel(UUID buildingId, int level);

    @Query("SELECT f FROM Floor f LEFT JOIN FETCH f.floorPath WHERE f.id = :id")
    Optional<Floor> findByIdWithPath(@Param("id") UUID id);

    @Query("SELECT f FROM Floor f " +
           "LEFT JOIN FETCH f.floorPath fp " +
           "LEFT JOIN FETCH fp.segments " +
           "WHERE f.id = :id")
    Optional<Floor> findByIdWithPathAndSegments(@Param("id") UUID id);

    boolean existsByBuildingIdAndLevel(UUID buildingId, int level);
}
