package com.netsentinel.dto;

import java.util.List;

public record ScanCompareResponse(
        String scanAId,
        String scanBId,
        String scanATarget,
        String scanBTarget,
        String scanADate,
        String scanBDate,
        List<ComparedFinding> newFindings,
        List<ComparedFinding> resolvedFindings,
        List<ComparedFinding> persistentFindings,
        int newCount,
        int resolvedCount,
        int persistentCount
) {
    public record ComparedFinding(
            String severity,
            String title,
            String host,
            int port,
            String service,
            List<String> relatedCves
    ) {}
}
