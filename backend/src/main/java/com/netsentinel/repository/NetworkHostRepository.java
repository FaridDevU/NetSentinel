package com.netsentinel.repository;

import com.netsentinel.entity.NetworkHost;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NetworkHostRepository extends JpaRepository<NetworkHost, UUID> {
    List<NetworkHost> findByScanJobId(UUID scanJobId);
}
