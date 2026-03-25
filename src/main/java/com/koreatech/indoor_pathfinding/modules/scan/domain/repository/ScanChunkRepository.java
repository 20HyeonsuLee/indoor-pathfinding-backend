package com.koreatech.indoor_pathfinding.modules.scan.domain.repository;

import com.koreatech.indoor_pathfinding.modules.scan.domain.model.ScanChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ScanChunkRepository extends JpaRepository<ScanChunk, UUID> {

    List<ScanChunk> findByFloorIdOrderByUploadOrderAsc(UUID floorId);

    List<ScanChunk> findByFloorIdAndActiveOrderByUploadOrderAsc(UUID floorId, boolean active);

    Optional<ScanChunk> findFirstByFloorIdOrderByUploadOrderDesc(UUID floorId);

    int countByFloorIdAndActive(UUID floorId, boolean active);
}
