package com.netsentinel.dto;

public record AssetDto(
        String ip,
        String hostname,
        String os,
        String macAddress,
        String vendor,
        String lastScanDate,
        String lastScanId,
        int openPorts,
        int totalCves,
        int criticalCves,
        int highCves,
        String riskLevel
) {}
