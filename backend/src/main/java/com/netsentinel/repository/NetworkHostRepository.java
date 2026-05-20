package com.netsentinel.repository;

import com.netsentinel.entity.NetworkHost;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NetworkHostRepository extends JpaRepository<NetworkHost, UUID> {
    List<NetworkHost> findByScanJobId(UUID scanJobId);

    @org.springframework.data.jpa.repository.Query(
        "SELECT h FROM NetworkHost h JOIN FETCH h.scanJob j WHERE j.status = 'COMPLETED' ORDER BY j.completedAt DESC NULLS LAST"
    )
    List<NetworkHost> findAllFromCompletedScans();
}
