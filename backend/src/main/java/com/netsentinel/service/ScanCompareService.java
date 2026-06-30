package com.netsentinel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netsentinel.dto.AnalysisReport;
import com.netsentinel.dto.ScanCompareResponse;
import com.netsentinel.entity.ScanJob;
import com.netsentinel.repository.ScanJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ScanCompareService {

    private final ScanJobRepository scanJobRepository;
    private final ObjectMapper objectMapper;

    public ScanCompareService(ScanJobRepository scanJobRepository, ObjectMapper objectMapper) {
        this.scanJobRepository = scanJobRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ScanCompareResponse compare(UUID scanAId, UUID scanBId) {
        ScanJob scanA = scanJobRepository.findById(scanAId)
                .orElseThrow(() -> new IllegalArgumentException("Scan A not found: " + scanAId));
        ScanJob scanB = scanJobRepository.findById(scanBId)
                .orElseThrow(() -> new IllegalArgumentException("Scan B not found: " + scanBId));

        Map<String, AnalysisReport.Finding> findingsA = loadFindings(scanA);
        Map<String, AnalysisReport.Finding> findingsB = loadFindings(scanB);

        Set<String> keysA = findingsA.keySet();
        Set<String> keysB = findingsB.keySet();

        List<ScanCompareResponse.ComparedFinding> newFindings = keysB.stream()
                .filter(k -> !keysA.contains(k))
                .map(k -> toCompared(findingsB.get(k)))
                .toList();

        List<ScanCompareResponse.ComparedFinding> resolvedFindings = keysA.stream()
                .filter(k -> !keysB.contains(k))
                .map(k -> toCompared(findingsA.get(k)))
                .toList();

        List<ScanCompareResponse.ComparedFinding> persistentFindings = keysA.stream()
                .filter(keysB::contains)
                .map(k -> toCompared(findingsA.get(k)))
                .toList();

        return new ScanCompareResponse(
                scanAId.toString(), scanBId.toString(),
                scanA.getTarget(), scanB.getTarget(),
                scanA.getStartedAt().toString(),
                scanB.getStartedAt().toString(),
                newFindings, resolvedFindings, persistentFindings,
                newFindings.size(), resolvedFindings.size(), persistentFindings.size()
        );
    }

    private Map<String, AnalysisReport.Finding> loadFindings(ScanJob job) {
        if (job.getAnalysisReport() == null) return Map.of();
        try {
            AnalysisReport report = objectMapper.readValue(job.getAnalysisReport(), AnalysisReport.class);
            Map<String, AnalysisReport.Finding> map = new LinkedHashMap<>();
            for (AnalysisReport.Finding f : report.findings()) {
                String key = findingKey(f);
                map.put(key, f);
            }
            return map;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String findingKey(AnalysisReport.Finding f) {
        if (!f.relatedCves().isEmpty()) {
            return f.relatedCves().stream().sorted().collect(Collectors.joining(","))
                    + "::" + f.host() + "::" + f.port();
        }
        return f.severity() + "::" + f.host() + "::" + f.port() + "::" + f.service();
    }

    private ScanCompareResponse.ComparedFinding toCompared(AnalysisReport.Finding f) {
        return new ScanCompareResponse.ComparedFinding(
                f.severity(), f.title(), f.host(), f.port(), f.service(), f.relatedCves()
        );
    }
}
