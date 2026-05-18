package com.netsentinel.dto;

import com.netsentinel.enums.ScanStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ScanResultsResponse(
        UUID id,
        String target,
        ScanStatus status,
        Instant startedAt,
        Instant completedAt,
        List<HostDto> hosts,
        AnalysisReport analysis
) {
    public record HostDto(
            UUID id,
            String ip,
            String hostname,
            String os,
            String macAddress,
            String vendor,
            List<PortDto> ports,
            List<WebFindingDto> webFindings
    ) {}

    public record PortDto(
            UUID id,
            int portNumber,
            String protocol,
            String state,
            String service,
            String version,
            List<CveDto> cves
    ) {}

    public record CveDto(
            String cveId,
            String description,
            Double cvssScore,
            String cvssVector,
            String nvdUrl
    ) {}

    public record WebFindingDto(
            String tool,
            String url,
            Integer statusCode,
            String description,
            String severity
    ) {}
}
