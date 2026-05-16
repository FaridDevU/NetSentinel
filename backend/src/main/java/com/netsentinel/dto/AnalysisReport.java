package com.netsentinel.dto;

import java.util.List;

public record AnalysisReport(
        String riskLevel,
        double riskScore,
        String summary,
        List<Finding> findings,
        List<HostSummary> hostAnalysis,
        List<String> recommendations
) {
    public record Finding(
            String severity,
            String title,
            String detail,
            String host,
            int port,
            String service,
            List<String> relatedCves
    ) {}

    public record HostSummary(
            String ip,
            String riskLevel,
            int openPorts,
            int totalCves,
            String summary
    ) {}
}
