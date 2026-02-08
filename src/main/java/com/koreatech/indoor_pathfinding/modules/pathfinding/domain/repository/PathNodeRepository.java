package com.koreatech.indoor_pathfinding.modules.pathfinding.domain.repository;

import com.koreatech.indoor_pathfinding.modules.pathfinding.domain.model.PathNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PathNodeRepository extends JpaRepository<PathNode, UUID> {

    List<PathNode> findByFloorId(UUID floorId);

    @Query("SELECT n FROM PathNode n WHERE n.floor.building.id = :buildingId")
    List<PathNode> findByBuildingId(@Param("buildingId") UUID buildingId);

    @Query("SELECT n FROM PathNode n WHERE n.floor.building.id = :buildingId AND n.poiName IS NOT NULL")
    List<PathNode> findPoisByBuildingId(@Param("buildingId") UUID buildingId);

    @Query("SELECT n FROM PathNode n WHERE n.floor.building.id = :buildingId " +
           "AND LOWER(n.poiName) LIKE LOWER(CONCAT('%', :query, '%'))")
    List<PathNode> searchPoisByBuildingIdAndName(
        @Param("buildingId") UUID buildingId,
        @Param("query") String query
    );

    @Query("SELECT n FROM PathNode n WHERE n.floor.building.id = :buildingId " +
           "AND LOWER(n.poiName) = LOWER(:poiName)")
    Optional<PathNode> findByBuildingIdAndPoiName(
        @Param("buildingId") UUID buildingId,
        @Param("poiName") String poiName
    );

    @Query("SELECT n FROM PathNode n WHERE n.floor.id = :floorId " +
           "ORDER BY ((n.x - :x) * (n.x - :x) + (n.y - :y) * (n.y - :y) + (n.z - :z) * (n.z - :z)) ASC " +
           "LIMIT 1")
    Optional<PathNode> findNearestNodeOnFloor(
        @Param("floorId") UUID floorId,
        @Param("x") double x,
        @Param("y") double y,
        @Param("z") double z
    );

    @Query("SELECT n FROM PathNode n WHERE n.verticalPassage.id = :passageId")
    List<PathNode> findByVerticalPassageId(@Param("passageId") UUID passageId);

    void deleteByFloorId(UUID floorId);

    @Modifying
    @Query("DELETE FROM PathNode n WHERE n.floor.building.id = :buildingId")
    void deleteByBuildingId(@Param("buildingId") UUID buildingId);
}
