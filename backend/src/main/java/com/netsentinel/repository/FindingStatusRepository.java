package com.netsentinel.repository;

import com.netsentinel.entity.FindingStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FindingStatusRepository extends JpaRepository<FindingStatus, UUID> {
    Optional<FindingStatus> findByScanJobIdAndFindingKey(UUID scanJobId, String findingKey);
    List<FindingStatus> findAllByScanJobId(UUID scanJobId);
}
