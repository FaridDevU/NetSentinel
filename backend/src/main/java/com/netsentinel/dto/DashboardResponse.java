package com.netsentinel.dto;

import java.util.List;
import java.util.Map;

public record DashboardResponse(
        long totalScans,
        long completedScans,
        long failedScans,
        long activeScans,
        long totalHosts,
        long totalCves,
        Map<String, Long> cvesBySeverity,
        double averageRiskScore,
        List<RecentScanEntry> recentScans
) {
    public record RecentScanEntry(
            String id,
            String target,
            String status,
            String startedAt,
            String completedAt,
            String riskLevel,
            Double riskScore,
            int hostCount
    ) {}
}
