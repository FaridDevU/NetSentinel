package com.netsentinel.repository;

import com.netsentinel.entity.ScanJob;
import com.netsentinel.enums.ScanStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ScanJobRepository extends JpaRepository<ScanJob, UUID> {
    List<ScanJob> findAllByOrderByStartedAtDesc();
    List<ScanJob> findByStatus(ScanStatus status);
}
