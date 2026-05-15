package com.netsentinel.repository;

import com.netsentinel.entity.NetworkPort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NetworkPortRepository extends JpaRepository<NetworkPort, UUID> {
    List<NetworkPort> findByHostId(UUID hostId);
}
