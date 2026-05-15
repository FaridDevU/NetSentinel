package com.netsentinel.service;

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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);

    private final ScanJobRepository scanJobRepository;
    private final SandboxService sandboxService;
    private final NmapParserService nmapParserService;
    private final NvdService nvdService;
    private final ScanJobUpdater scanJobUpdater;

    public ScanService(ScanJobRepository scanJobRepository,
                       SandboxService sandboxService,
                       NmapParserService nmapParserService,
                       NvdService nvdService,
                       ScanJobUpdater scanJobUpdater) {
        this.scanJobRepository = scanJobRepository;
        this.sandboxService = sandboxService;
        this.nmapParserService = nmapParserService;
        this.nvdService = nvdService;
        this.scanJobUpdater = scanJobUpdater;
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

        SandboxService.SandboxResult result = sandboxService.runNmap(job.getTarget(), job.getParameters());

        if (!result.success()) {
            scanJobUpdater.markFailed(scanJobId, result.error());
            log.error("Scan {} failed: {}", scanJobId, result.error());
            return;
        }

        List<NetworkHost> hosts = nmapParserService.parse(result.output(), job);

        for (NetworkHost host : hosts) {
            for (NetworkPort port : host.getPorts()) {
                if ("open".equals(port.getState()) && port.getService() != null && port.getVersion() != null) {
                    List<CveEntry> cves = nvdService.lookupCves(port.getService(), port.getVersion(), port);
                    port.setCves(cves);
                }
            }
        }

        scanJobUpdater.saveResults(scanJobId, result.output(), hosts);
        log.info("Scan {} completed. Found {} hosts.", scanJobId, hosts.size());
    }

    @Transactional(readOnly = true)
    public Optional<ScanStatusResponse> getStatus(UUID id) {
        return scanJobRepository.findById(id).map(job -> new ScanStatusResponse(
                job.getId(),
                job.getTarget(),
                job.getStatus(),
                job.getStartedAt(),
                job.getCompletedAt(),
                job.getErrorMessage()
        ));
    }

    @Transactional(readOnly = true)
    public Optional<ScanResultsResponse> getResults(UUID id) {
        return scanJobRepository.findById(id).map(this::toResultsResponse);
    }

    @Transactional(readOnly = true)
    public List<ScanStatusResponse> getHistory() {
        return scanJobRepository.findAllByOrderByStartedAtDesc().stream()
                .map(job -> new ScanStatusResponse(
                        job.getId(),
                        job.getTarget(),
                        job.getStatus(),
                        job.getStartedAt(),
                        job.getCompletedAt(),
                        job.getErrorMessage()
                ))
                .toList();
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

        return new ScanResultsResponse(
                job.getId(),
                job.getTarget(),
                job.getStatus(),
                job.getStartedAt(),
                job.getCompletedAt(),
                hostDtos,
                job.getAiReport()
        );
    }
}
