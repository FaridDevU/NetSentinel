package com.netsentinel.controller;

import com.netsentinel.dto.ScanRequest;
import com.netsentinel.dto.ScanResultsResponse;
import com.netsentinel.dto.ScanStatusResponse;
import com.netsentinel.entity.ScanJob;
import com.netsentinel.service.ScanService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ScanController {

    private final ScanService scanService;

    public ScanController(ScanService scanService) {
        this.scanService = scanService;
    }

    @PostMapping("/scan/start")
    public ResponseEntity<?> startScan(@RequestBody ScanRequest request) {
        if (request.target() == null || request.target().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Target is required"));
        }

        List<String> parameters = request.parameters() != null ? request.parameters() : List.of("-sV", "-T4");

        ScanJob job = scanService.createScan(request.target(), parameters);
        scanService.executeScan(job.getId());

        return ResponseEntity.accepted().body(Map.of(
                "id", job.getId(),
                "target", job.getTarget(),
                "status", job.getStatus()
        ));
    }

    @GetMapping("/scan/{id}/status")
    public ResponseEntity<?> getScanStatus(@PathVariable UUID id) {
        return scanService.getStatus(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/scan/{id}/results")
    public ResponseEntity<?> getScanResults(@PathVariable UUID id) {
        return scanService.getResults(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/history")
    public ResponseEntity<List<ScanStatusResponse>> getHistory() {
        return ResponseEntity.ok(scanService.getHistory());
    }
}
