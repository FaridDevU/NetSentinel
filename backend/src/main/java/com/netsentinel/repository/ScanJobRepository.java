package com.netsentinel.repository;

import com.netsentinel.entity.ScanJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

public interface ScanJobRepository extends JpaRepository<ScanJob, UUID> {
    Page<ScanJob> findAllByOrderByStartedAtDesc(Pageable pageable);

    @Transactional
    @Modifying
    @Query("UPDATE ScanJob j SET j.status = 'FAILED', j.errorMessage = 'Backend reiniciado durante el escaneo', j.completedAt = :now WHERE j.status IN ('PENDING', 'RUNNING')")
    int markStuckJobsAsFailed(@Param("now") Instant now);
}
