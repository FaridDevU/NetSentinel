package com.netsentinel.repository;

import com.netsentinel.entity.CveEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface CveEntryRepository extends JpaRepository<CveEntry, UUID> {
    List<CveEntry> findByPortId(UUID portId);

    @Query("SELECT COUNT(c) FROM CveEntry c WHERE c.cvssScore >= :minScore AND c.cvssScore < :maxScore")
    long countByScoreRange(@Param("minScore") double minScore, @Param("maxScore") double maxScore);

    @Query("SELECT COUNT(c) FROM CveEntry c WHERE c.cvssScore >= :minScore")
    long countByScoreAtLeast(@Param("minScore") double minScore);
}
