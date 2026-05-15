package com.netsentinel.service;

import com.netsentinel.entity.NetworkHost;
import com.netsentinel.entity.ScanJob;
import com.netsentinel.enums.ScanStatus;
import com.netsentinel.repository.ScanJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ScanJobUpdater {

    private final ScanJobRepository scanJobRepository;

    public ScanJobUpdater(ScanJobRepository scanJobRepository) {
        this.scanJobRepository = scanJobRepository;
    }

    @Transactional
    public ScanJob markRunning(UUID scanJobId) {
        ScanJob job = scanJobRepository.findById(scanJobId)
                .orElseThrow(() -> new IllegalArgumentException("ScanJob not found: " + scanJobId));
        job.setStatus(ScanStatus.RUNNING);
        return scanJobRepository.save(job);
    }

    @Transactional
    public void markFailed(UUID scanJobId, String errorMessage) {
        scanJobRepository.findById(scanJobId).ifPresent(job -> {
            job.setStatus(ScanStatus.FAILED);
            job.setErrorMessage(errorMessage);
            job.setCompletedAt(Instant.now());
            scanJobRepository.save(job);
        });
    }

    @Transactional
    public void saveResults(UUID scanJobId, String rawOutput, List<NetworkHost> hosts) {
        scanJobRepository.findById(scanJobId).ifPresent(job -> {
            job.setRawOutput(rawOutput);
            hosts.forEach(h -> h.setScanJob(job));
            job.getHosts().clear();
            job.getHosts().addAll(hosts);
            job.setStatus(ScanStatus.COMPLETED);
            job.setCompletedAt(Instant.now());
            scanJobRepository.save(job);
        });
    }

    @Transactional
    public boolean cancelScan(UUID scanJobId) {
        return scanJobRepository.findById(scanJobId).map(job -> {
            if (job.getStatus() == ScanStatus.PENDING || job.getStatus() == ScanStatus.RUNNING) {
                job.setStatus(ScanStatus.CANCELLED);
                job.setCompletedAt(Instant.now());
                scanJobRepository.save(job);
                return true;
            }
            return false;
        }).orElse(false);
    }

    @Transactional
    public void deleteScan(UUID scanJobId) {
        scanJobRepository.deleteById(scanJobId);
    }

    @Transactional(readOnly = true)
    public boolean isCancelled(UUID scanJobId) {
        return scanJobRepository.findById(scanJobId)
                .map(job -> job.getStatus() == ScanStatus.CANCELLED)
                .orElse(true);
    }
}
