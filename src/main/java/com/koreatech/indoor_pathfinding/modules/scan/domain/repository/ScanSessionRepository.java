package com.koreatech.indoor_pathfinding.modules.scan.domain.repository;

import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ScanSession;
import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ScanStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ScanSessionRepository extends JpaRepository<ScanSession, UUID> {

    List<ScanSession> findByBuildingId(UUID buildingId);

    List<ScanSession> findByBuildingIdAndStatus(UUID buildingId, ScanStatus status);

    Optional<ScanSession> findFirstByBuildingIdOrderByCreatedAtDesc(UUID buildingId);

    List<ScanSession> findByStatus(ScanStatus status);
}
