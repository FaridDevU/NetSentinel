package com.netsentinel.repository;

import com.netsentinel.entity.ScanJob;
import com.netsentinel.enums.ScanStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ScanJobRepository extends JpaRepository<ScanJob, UUID> {
    Page<ScanJob> findAllByOrderByStartedAtDesc(Pageable pageable);
    List<ScanJob> findByStatus(ScanStatus status);
}
