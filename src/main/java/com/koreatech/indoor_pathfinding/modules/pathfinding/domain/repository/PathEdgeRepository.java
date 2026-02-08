package com.koreatech.indoor_pathfinding.modules.pathfinding.domain.repository;

import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.PathEdge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PathEdgeRepository extends JpaRepository<PathEdge, UUID> {

    @Query("SELECT e FROM PathEdge e WHERE e.fromNode.id = :nodeId OR " +
           "(e.toNode.id = :nodeId AND e.isBidirectional = true)")
    List<PathEdge> findOutgoingEdges(@Param("nodeId") UUID nodeId);

    @Query("SELECT e FROM PathEdge e WHERE e.fromNode.floor.building.id = :buildingId " +
           "OR e.toNode.floor.building.id = :buildingId")
    List<PathEdge> findByBuildingId(@Param("buildingId") UUID buildingId);

    @Query("SELECT e FROM PathEdge e WHERE e.fromNode.floor.id = :floorId " +
           "OR e.toNode.floor.id = :floorId")
    List<PathEdge> findByFloorId(@Param("floorId") UUID floorId);

    @Modifying
    @Query("DELETE FROM PathEdge e WHERE e.fromNode.floor.id = :floorId " +
           "OR e.toNode.floor.id = :floorId")
    void deleteByFloorId(@Param("floorId") UUID floorId);

    @Modifying
    @Query("DELETE FROM PathEdge e WHERE e.fromNode.floor.building.id = :buildingId " +
           "OR e.toNode.floor.building.id = :buildingId")
    void deleteByBuildingId(@Param("buildingId") UUID buildingId);
}
