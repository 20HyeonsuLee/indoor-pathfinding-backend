package com.koreatech.indoor_pathfinding.modules.scan.domain.repository;

import com.koreatech.indoor_pathfinding.modules.scan.domain.model.MergedScan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MergedScanRepository extends JpaRepository<MergedScan, UUID> {

    Optional<MergedScan> findByFloorId(UUID floorId);

    void deleteByFloorId(UUID floorId);
}
