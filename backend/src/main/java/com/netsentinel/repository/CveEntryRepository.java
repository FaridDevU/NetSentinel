package com.netsentinel.repository;

import com.netsentinel.entity.CveEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CveEntryRepository extends JpaRepository<CveEntry, UUID> {
    List<CveEntry> findByPortId(UUID portId);
}
