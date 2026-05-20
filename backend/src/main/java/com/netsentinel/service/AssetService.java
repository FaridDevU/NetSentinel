package com.netsentinel.service;

import com.netsentinel.dto.AssetDto;
import com.netsentinel.entity.CveEntry;
import com.netsentinel.entity.NetworkHost;
import com.netsentinel.entity.NetworkPort;
import com.netsentinel.repository.NetworkHostRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AssetService {

    private final NetworkHostRepository hostRepository;

    public AssetService(NetworkHostRepository hostRepository) {
        this.hostRepository = hostRepository;
    }

    @Transactional(readOnly = true)
    public List<AssetDto> getAssets() {
        List<NetworkHost> all = hostRepository.findAllFromCompletedScans();

        Map<String, NetworkHost> latestByIp = new LinkedHashMap<>();
        for (NetworkHost h : all) {
            latestByIp.putIfAbsent(h.getIp(), h);
        }

        return latestByIp.values().stream().map(h -> {
            List<NetworkPort> ports = h.getPorts();
            long openPorts = ports.stream().filter(p -> "open".equals(p.getState())).count();
            long totalCves = ports.stream().mapToLong(p -> p.getCves().size()).sum();
            long criticalCves = ports.stream()
                    .flatMap(p -> p.getCves().stream())
                    .filter(c -> c.getCvssScore() != null && c.getCvssScore() >= 9.0)
                    .count();
            long highCves = ports.stream()
                    .flatMap(p -> p.getCves().stream())
                    .filter(c -> c.getCvssScore() != null && c.getCvssScore() >= 7.0 && c.getCvssScore() < 9.0)
                    .count();

            String riskLevel;
            if (criticalCves > 0) riskLevel = "CRITICAL";
            else if (highCves > 0) riskLevel = "HIGH";
            else if (totalCves > 0) riskLevel = "MEDIUM";
            else if (openPorts > 0) riskLevel = "LOW";
            else riskLevel = "INFO";

            var job = h.getScanJob();
            return new AssetDto(
                    h.getIp(),
                    h.getHostname(),
                    h.getOs(),
                    h.getMacAddress(),
                    h.getVendor(),
                    job.getCompletedAt() != null ? job.getCompletedAt().toString() : null,
                    job.getId().toString(),
                    (int) openPorts,
                    (int) totalCves,
                    (int) criticalCves,
                    (int) highCves,
                    riskLevel
            );
        }).toList();
    }
}
