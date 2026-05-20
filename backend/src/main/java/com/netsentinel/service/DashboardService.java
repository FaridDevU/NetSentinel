package com.netsentinel.service;

import com.netsentinel.dto.DashboardResponse;
import com.netsentinel.entity.ScanJob;
import com.netsentinel.enums.ScanStatus;
import com.netsentinel.repository.CveEntryRepository;
import com.netsentinel.repository.NetworkHostRepository;
import com.netsentinel.repository.ScanJobRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DashboardService {

    private final ScanJobRepository scanJobRepository;
    private final NetworkHostRepository hostRepository;
    private final CveEntryRepository cveRepository;

    public DashboardService(ScanJobRepository scanJobRepository,
                            NetworkHostRepository hostRepository,
                            CveEntryRepository cveRepository) {
        this.scanJobRepository = scanJobRepository;
        this.hostRepository = hostRepository;
        this.cveRepository = cveRepository;
    }

    @Transactional(readOnly = true)
    public DashboardResponse getDashboard() {
        long total = scanJobRepository.count();
        long completed = scanJobRepository.countByStatus(ScanStatus.COMPLETED);
        long failed = scanJobRepository.countByStatus(ScanStatus.FAILED);
        long pending = scanJobRepository.countByStatus(ScanStatus.PENDING);
        long running = scanJobRepository.countByStatus(ScanStatus.RUNNING);
        long totalHosts = hostRepository.count();
        long totalCves = cveRepository.count();

        Map<String, Long> cvesBySeverity = new LinkedHashMap<>();
        cvesBySeverity.put("CRITICAL", cveRepository.countByScoreAtLeast(9.0));
        cvesBySeverity.put("HIGH", cveRepository.countByScoreRange(7.0, 9.0));
        cvesBySeverity.put("MEDIUM", cveRepository.countByScoreRange(4.0, 7.0));
        cvesBySeverity.put("LOW", cveRepository.countByScoreRange(0.1, 4.0));

        double avgRisk = scanJobRepository.findAllByOrderByStartedAtDesc(PageRequest.of(0, 100))
                .getContent().stream()
                .filter(j -> j.getRiskScore() != null)
                .mapToDouble(ScanJob::getRiskScore)
                .average()
                .orElse(0.0);

        List<DashboardResponse.RecentScanEntry> recent = scanJobRepository
                .findAllByOrderByStartedAtDesc(PageRequest.of(0, 5))
                .getContent().stream()
                .map(j -> new DashboardResponse.RecentScanEntry(
                        j.getId().toString(),
                        j.getTarget(),
                        j.getStatus().name(),
                        j.getStartedAt().toString(),
                        j.getCompletedAt() != null ? j.getCompletedAt().toString() : null,
                        j.getRiskLevel(),
                        j.getRiskScore(),
                        j.getHosts().size()
                ))
                .toList();

        return new DashboardResponse(
                total, completed, failed, pending + running,
                totalHosts, totalCves,
                cvesBySeverity,
                Math.round(avgRisk * 10.0) / 10.0,
                recent
        );
    }
}
