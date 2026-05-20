package com.netsentinel.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netsentinel.dto.AnalysisReport;
import com.netsentinel.dto.PagedResponse;
import com.netsentinel.dto.ScanResultsResponse;
import com.netsentinel.dto.ScanStatusResponse;
import com.netsentinel.entity.CveEntry;
import com.netsentinel.entity.NetworkHost;
import com.netsentinel.entity.NetworkPort;
import com.netsentinel.entity.ScanJob;
import com.netsentinel.entity.WebFinding;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Set<Integer> WEB_PORTS = Set.of(80, 443, 8080, 8443, 3000, 8000, 8081, 8888);

    private final Map<UUID, List<String>> scanLogs = new ConcurrentHashMap<>();

    private final ScanJobRepository scanJobRepository;
    private final SandboxService sandboxService;
    private final NmapParserService nmapParserService;
    private final NvdService nvdService;
    private final ScanJobUpdater scanJobUpdater;
    private final AnalysisService analysisService;
    private final GobusterParserService gobusterParserService;
    private final NiktoParserService niktoParserService;
    private final ObjectMapper objectMapper;

    public ScanService(ScanJobRepository scanJobRepository,
                       SandboxService sandboxService,
                       NmapParserService nmapParserService,
                       NvdService nvdService,
                       ScanJobUpdater scanJobUpdater,
                       AnalysisService analysisService,
                       GobusterParserService gobusterParserService,
                       NiktoParserService niktoParserService,
                       ObjectMapper objectMapper) {
        this.scanJobRepository = scanJobRepository;
        this.sandboxService = sandboxService;
        this.nmapParserService = nmapParserService;
        this.nvdService = nvdService;
        this.scanJobUpdater = scanJobUpdater;
        this.analysisService = analysisService;
        this.gobusterParserService = gobusterParserService;
        this.niktoParserService = niktoParserService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void recoverStuckScans() {
        int recovered = scanJobRepository.markStuckJobsAsFailed(Instant.now());
        if (recovered > 0) {
            log.warn("Recovered {} stuck scan(s) from previous run", recovered);
        }
    }

    @Transactional
    public ScanJob createScan(String target, List<String> parameters) {
        ScanJob job = new ScanJob(target, parameters);
        return scanJobRepository.save(job);
    }

    @Async("scanExecutor")
    public void executeScan(UUID scanJobId) {
        try {
            runScan(scanJobId);
        } finally {
            persistLogs(scanJobId);
        }
    }

    private void runScan(UUID scanJobId) {
        ScanJob job = scanJobUpdater.markRunning(scanJobId);
        log.info("Starting scan {} for target {}", scanJobId, job.getTarget());
        addLog(scanJobId, "Analisis iniciado — objetivo: " + job.getTarget());

        String flags = String.join(" ", job.getParameters());
        addLog(scanJobId, "Ejecutando nmap " + (flags.isBlank() ? "(por defecto)" : flags) + " ...");

        SandboxService.SandboxResult result = sandboxService.runNmap(job.getTarget(), job.getParameters());

        if (scanJobUpdater.isCancelled(scanJobId)) {
            addLog(scanJobId, "Analisis cancelado.");
            return;
        }

        if (!result.success()) {
            scanJobUpdater.markFailed(scanJobId, result.error());
            log.error("Scan {} failed: {}", scanJobId, result.error());
            addLog(scanJobId, "Error en nmap: " + result.error());
            return;
        }

        List<NetworkHost> hosts = nmapParserService.parse(result.output(), job);
        addLog(scanJobId, "nmap finalizado — " + hosts.size() + " dispositivo(s) encontrado(s)");

        long openPorts = hosts.stream()
                .flatMap(h -> h.getPorts().stream())
                .filter(p -> "open".equals(p.getState()) && p.getService() != null)
                .count();

        if (openPorts > 0) {
            addLog(scanJobId, "Consultando base de datos de vulnerabilidades para " + openPorts + " servicio(s)...");
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
            addLog(scanJobId, "Analisis cancelado.");
            return;
        }

        runWebScans(scanJobId, hosts);

        if (scanJobUpdater.isCancelled(scanJobId)) {
            addLog(scanJobId, "Analisis cancelado.");
            return;
        }

        scanJobUpdater.saveResults(scanJobId, result.output(), hosts);
        log.info("Scan {} completed with {} hosts", scanJobId, hosts.size());

        addLog(scanJobId, "Calculando nivel de riesgo...");
        try {
            AnalysisReport report = analysisService.analyze(job.getTarget(), hosts);
            String reportJson = objectMapper.writeValueAsString(report);
            scanJobUpdater.saveAnalysis(scanJobId, reportJson, report.riskLevel(), report.riskScore());
            log.info("Analysis saved for scan {} — risk: {}", scanJobId, report.riskLevel());

            int totalCves = hosts.stream()
                    .flatMap(h -> h.getPorts().stream())
                    .mapToInt(p -> p.getCves().size()).sum();
            int totalWebFindings = hosts.stream()
                    .mapToInt(h -> h.getWebFindings().size()).sum();
            addLog(scanJobId, "Listo — " + hosts.size() + " dispositivo(s), " + totalCves
                    + " CVE(s), " + totalWebFindings + " hallazgo(s) web. Riesgo: " + report.riskLevel());
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize analysis for scan {}: {}", scanJobId, e.getMessage());
            addLog(scanJobId, "Listo — " + hosts.size() + " dispositivo(s) encontrado(s).");
        }
    }

    private void runWebScans(UUID scanJobId, List<NetworkHost> hosts) {
        for (NetworkHost host : hosts) {
            List<NetworkPort> openWebPorts = host.getPorts().stream()
                    .filter(p -> "open".equals(p.getState()) && WEB_PORTS.contains(p.getPortNumber()))
                    .toList();

            if (openWebPorts.isEmpty()) continue;

            for (NetworkPort port : openWebPorts) {
                if (scanJobUpdater.isCancelled(scanJobId)) return;

                String scheme = (port.getPortNumber() == 443 || port.getPortNumber() == 8443) ? "https" : "http";
                String url = scheme + "://" + host.getIp() + ":" + port.getPortNumber();

                addLog(scanJobId, "Analizando servicios web en " + host.getIp() + ":" + port.getPortNumber() + " ...");

                SandboxService.SandboxResult gobusterResult = sandboxService.runGobuster(url);
                if (gobusterResult.success() && gobusterResult.output() != null) {
                    List<WebFinding> gf = gobusterParserService.parse(gobusterResult.output(), host, url);
                    host.getWebFindings().addAll(gf);
                    if (!gf.isEmpty()) {
                        addLog(scanJobId, "  gobuster: " + gf.size() + " ruta(s) en " + url);
                    }
                } else {
                    log.debug("Gobuster skipped or failed for {}: {}", url, gobusterResult.error());
                }

                SandboxService.SandboxResult niktoResult = sandboxService.runNikto(url);
                if (niktoResult.success() && niktoResult.output() != null) {
                    List<WebFinding> nf = niktoParserService.parse(niktoResult.output(), host, url);
                    host.getWebFindings().addAll(nf);
                    if (!nf.isEmpty()) {
                        addLog(scanJobId, "  nikto: " + nf.size() + " hallazgo(s) en " + url);
                    }
                } else {
                    log.debug("Nikto skipped or failed for {}: {}", url, niktoResult.error());
                }
            }
        }
    }

    private void persistLogs(UUID scanJobId) {
        try {
            List<String> logs = scanLogs.getOrDefault(scanJobId, List.of());
            String logsJson = objectMapper.writeValueAsString(logs);
            scanJobUpdater.saveLogs(scanJobId, logsJson);
        } catch (Exception e) {
            log.warn("Failed to persist logs for scan {}: {}", scanJobId, e.getMessage());
        } finally {
            scanLogs.remove(scanJobId);
        }
    }

    private void addLog(UUID id, String msg) {
        String ts = LocalTime.now().format(TIME_FMT);
        scanLogs.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>())
                .add("[" + ts + "] " + msg);
    }

    public List<String> getLogs(UUID id) {
        List<String> inMemory = scanLogs.get(id);
        if (inMemory != null && !inMemory.isEmpty()) {
            return inMemory;
        }
        return scanJobRepository.findById(id)
                .map(job -> {
                    if (job.getScanLogs() == null) return List.<String>of();
                    try {
                        return objectMapper.readValue(job.getScanLogs(), new TypeReference<List<String>>() {});
                    } catch (Exception e) {
                        return List.<String>of();
                    }
                })
                .orElse(List.of());
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
                job.getErrorMessage(),
                job.getRiskLevel(),
                job.getRiskScore()
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
                                .toList(),
                        host.getWebFindings().stream()
                                .map(wf -> new ScanResultsResponse.WebFindingDto(
                                        wf.getTool(),
                                        wf.getUrl(),
                                        wf.getStatusCode(),
                                        wf.getDescription(),
                                        wf.getSeverity()
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
