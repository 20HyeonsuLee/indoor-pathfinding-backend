package com.koreatech.indoor_pathfinding.modules.building.domain.repository;

import com.koreatech.indoor_pathfinding.modules.building.domain.model.Building;
import com.koreatech.indoor_pathfinding.modules.building.domain.model.BuildingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BuildingRepository extends JpaRepository<Building, UUID> {

    List<Building> findByStatus(BuildingStatus status);

    @Query("SELECT b FROM Building b LEFT JOIN FETCH b.floors WHERE b.id = :id")
    Optional<Building> findByIdWithFloors(@Param("id") UUID id);

    @Query("SELECT b FROM Building b " +
           "LEFT JOIN FETCH b.floors f " +
           "LEFT JOIN FETCH b.verticalPassages " +
           "WHERE b.id = :id")
    Optional<Building> findByIdWithFloorsAndPassages(@Param("id") UUID id);

    boolean existsByName(String name);
}
