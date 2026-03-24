package com.koreatech.indoor_pathfinding.modules.floor.domain.repository;

import com.koreatech.indoor_pathfinding.modules.floor.domain.model.Floor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FloorRepository extends JpaRepository<Floor, UUID> {

    List<Floor> findByBuildingIdOrderByLevelAsc(UUID buildingId);

    Optional<Floor> findByBuildingIdAndLevel(UUID buildingId, int level);

    boolean existsByBuildingIdAndLevel(UUID buildingId, int level);
}
