package com.netsentinel.service;

import com.netsentinel.entity.FindingStatus;
import com.netsentinel.enums.VulnStatus;
import com.netsentinel.repository.FindingStatusRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class FindingStatusService {

    private final FindingStatusRepository repository;

    public FindingStatusService(FindingStatusRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Map<String, String> getStatuses(UUID scanJobId) {
        Map<String, String> result = new LinkedHashMap<>();
        repository.findAllByScanJobId(scanJobId)
                .forEach(fs -> result.put(fs.getFindingKey(), fs.getStatus().name()));
        return result;
    }

    @Transactional
    public void updateStatus(UUID scanJobId, String findingKey, VulnStatus status) {
        FindingStatus fs = repository.findByScanJobIdAndFindingKey(scanJobId, findingKey)
                .orElseGet(() -> new FindingStatus(scanJobId, findingKey));
        fs.setStatus(status);
        fs.setUpdatedAt(Instant.now());
        repository.save(fs);
    }
}
