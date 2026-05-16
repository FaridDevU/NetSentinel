package com.netsentinel.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netsentinel.dto.AnalysisReport;
import com.netsentinel.dto.PagedResponse;
import com.netsentinel.dto.ScanResultsResponse;
import com.netsentinel.dto.ScanStatusResponse;
import com.netsentinel.entity.CveEntry;
import com.netsentinel.entity.NetworkHost;
import com.netsentinel.entity.NetworkPort;
import com.netsentinel.entity.ScanJob;
import com.netsentinel.enums.ScanStatus;
import com.netsentinel.repository.ScanJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final Map<UUID, List<String>> scanLogs = new ConcurrentHashMap<>();

    private final ScanJobRepository scanJobRepository;
    private final SandboxService sandboxService;
    private final NmapParserService nmapParserService;
    private final NvdService nvdService;
    private final ScanJobUpdater scanJobUpdater;
    private final AnalysisService analysisService;
    private final ObjectMapper objectMapper;

    public ScanService(ScanJobRepository scanJobRepository,
                       SandboxService sandboxService,
                       NmapParserService nmapParserService,
                       NvdService nvdService,
                       ScanJobUpdater scanJobUpdater,
                       AnalysisService analysisService,
                       ObjectMapper objectMapper) {
        this.scanJobRepository = scanJobRepository;
        this.sandboxService = sandboxService;
        this.nmapParserService = nmapParserService;
        this.nvdService = nvdService;
        this.scanJobUpdater = scanJobUpdater;
        this.analysisService = analysisService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ScanJob createScan(String target, List<String> parameters) {
        ScanJob job = new ScanJob(target, parameters);
        return scanJobRepository.save(job);
    }

    @Async("scanExecutor")
    public void executeScan(UUID scanJobId) {
        ScanJob job = scanJobUpdater.markRunning(scanJobId);
        log.info("Starting scan {} for target {}", scanJobId, job.getTarget());
        addLog(scanJobId, "Scan started — target: " + job.getTarget());

        String flags = String.join(" ", job.getParameters());
        addLog(scanJobId, "Running nmap " + (flags.isBlank() ? "(default)" : flags) + " ...");

        SandboxService.SandboxResult result = sandboxService.runNmap(job.getTarget(), job.getParameters());

        if (scanJobUpdater.isCancelled(scanJobId)) {
            log.info("Scan {} cancelled during sandbox execution", scanJobId);
            addLog(scanJobId, "Scan cancelled.");
            return;
        }

        if (!result.success()) {
            scanJobUpdater.markFailed(scanJobId, result.error());
            log.error("Scan {} failed: {}", scanJobId, result.error());
            addLog(scanJobId, "nmap error: " + result.error());
            return;
        }

        List<NetworkHost> hosts = nmapParserService.parse(result.output(), job);
        addLog(scanJobId, "nmap finished — " + hosts.size() + " host(s) found");

        long openPorts = hosts.stream()
                .flatMap(h -> h.getPorts().stream())
                .filter(p -> "open".equals(p.getState()) && p.getService() != null)
                .count();

        if (openPorts > 0) {
            addLog(scanJobId, "Checking CVE database for " + openPorts + " open service(s)...");
        }

        for (NetworkHost host : hosts) {
            for (NetworkPort port : host.getPorts()) {
                if ("open".equals(port.getState()) && port.getService() != null) {
                    List<CveEntry> cves = nvdService.lookupCves(port.getService(), port.getVersion(), port);
                    port.setCves(cves);
                    if (!cves.isEmpty()) {
                        addLog(scanJobId, "  " + host.getIp() + ":" + port.getPortNumber()
                                + " (" + port.getService() + ") — " + cves.size() + " CVE(s)");
                    }
                }
            }
        }

        if (scanJobUpdater.isCancelled(scanJobId)) {
            log.info("Scan {} cancelled before save", scanJobId);
            addLog(scanJobId, "Scan cancelled.");
            return;
        }

        scanJobUpdater.saveResults(scanJobId, result.output(), hosts);
        log.info("Scan {} completed with {} hosts", scanJobId, hosts.size());

        addLog(scanJobId, "Running security risk analysis...");
        try {
            AnalysisReport report = analysisService.analyze(job.getTarget(), hosts);
            String reportJson = objectMapper.writeValueAsString(report);
            scanJobUpdater.saveAnalysis(scanJobId, reportJson);
            log.info("Analysis saved for scan {} — risk: {}", scanJobId, report.riskLevel());

            int totalCves = hosts.stream()
                    .flatMap(h -> h.getPorts().stream())
                    .mapToInt(p -> p.getCves().size()).sum();
            addLog(scanJobId, "Done — " + hosts.size() + " host(s), " + totalCves + " CVE(s) found. Risk: " + report.riskLevel());
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize analysis for scan {}: {}", scanJobId, e.getMessage());
            addLog(scanJobId, "Done — " + hosts.size() + " host(s) found.");
        }
    }

    private void addLog(UUID id, String msg) {
        String ts = LocalTime.now().format(TIME_FMT);
        scanLogs.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>())
                .add("[" + ts + "] " + msg);
    }

    public List<String> getLogs(UUID id) {
        return scanLogs.getOrDefault(id, List.of());
    }

    public boolean cancelScan(UUID id) {
        return scanJobUpdater.cancelScan(id);
    }

    public void deleteScan(UUID id) {
        scanJobUpdater.deleteScan(id);
    }

    @Transactional(readOnly = true)
    public Optional<ScanStatusResponse> getStatus(UUID id) {
        return scanJobRepository.findById(id).map(this::toStatusResponse);
    }

    @Transactional(readOnly = true)
    public Optional<ScanResultsResponse> getResults(UUID id) {
        return scanJobRepository.findById(id).map(this::toResultsResponse);
    }

    @Transactional(readOnly = true)
    public PagedResponse<ScanStatusResponse> getHistory(int page, int size) {
        return PagedResponse.from(
                scanJobRepository.findAllByOrderByStartedAtDesc(PageRequest.of(page, size))
                        .map(this::toStatusResponse)
        );
    }

    private ScanStatusResponse toStatusResponse(ScanJob job) {
        return new ScanStatusResponse(
                job.getId(),
                job.getTarget(),
                job.getStatus(),
                job.getStartedAt(),
                job.getCompletedAt(),
                job.getErrorMessage()
        );
    }

    private ScanResultsResponse toResultsResponse(ScanJob job) {
        List<ScanResultsResponse.HostDto> hostDtos = job.getHosts().stream()
                .map(host -> new ScanResultsResponse.HostDto(
                        host.getId(),
                        host.getIp(),
                        host.getHostname(),
                        host.getOs(),
                        host.getMacAddress(),
                        host.getVendor(),
                        host.getPorts().stream()
                                .map(port -> new ScanResultsResponse.PortDto(
                                        port.getId(),
                                        port.getPortNumber(),
                                        port.getProtocol(),
                                        port.getState(),
                                        port.getService(),
                                        port.getVersion(),
                                        port.getCves().stream()
                                                .map(cve -> new ScanResultsResponse.CveDto(
                                                        cve.getCveId(),
                                                        cve.getDescription(),
                                                        cve.getCvssScore(),
                                                        cve.getCvssVector(),
                                                        cve.getNvdUrl()
                                                ))
                                                .toList()
                                ))
                                .toList()
                ))
                .toList();

        AnalysisReport analysis = null;
        if (job.getAiReport() != null) {
            try {
                analysis = objectMapper.readValue(job.getAiReport(), AnalysisReport.class);
            } catch (JsonProcessingException e) {
                log.warn("Could not deserialize analysis for scan {}", job.getId());
            }
        }

        return new ScanResultsResponse(
                job.getId(),
                job.getTarget(),
                job.getStatus(),
                job.getStartedAt(),
                job.getCompletedAt(),
                hostDtos,
                analysis
        );
    }
}
