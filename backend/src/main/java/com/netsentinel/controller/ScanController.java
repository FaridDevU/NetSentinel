package com.netsentinel.controller;

import com.netsentinel.dto.ErrorResponse;
import com.netsentinel.dto.PagedResponse;
import com.netsentinel.dto.ScanRequest;
import com.netsentinel.dto.ScanResultsResponse;
import com.netsentinel.dto.ScanStatusResponse;
import com.netsentinel.entity.ScanJob;
import com.netsentinel.enums.ScanStatus;
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

    private static final int MAX_PAGE_SIZE = 100;
    private static final java.util.regex.Pattern VALID_TARGET =
            java.util.regex.Pattern.compile("^[a-zA-Z0-9.\\-:/\\[\\]]{1,100}$");

    private final ScanService scanService;

    public ScanController(ScanService scanService) {
        this.scanService = scanService;
    }

    @PostMapping("/scan/start")
    public ResponseEntity<?> startScan(@RequestBody ScanRequest request) {
        if (request.target() == null || request.target().isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Target is required"));
        }

        if (!VALID_TARGET.matcher(request.target().trim()).matches()) {
            return ResponseEntity.badRequest().body(new ErrorResponse(
                    "Invalid target format. Accepted: IP, CIDR, hostname (no special characters)"));
        }

        List<String> parameters = request.parameters() != null ? request.parameters() : List.of("-sV", "-T4");

        ScanJob job = scanService.createScan(request.target().trim(), parameters);
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
                .orElse(ResponseEntity.status(404).body(new ErrorResponse("Scan not found: " + id)));
    }

    @GetMapping("/scan/{id}/results")
    public ResponseEntity<?> getScanResults(@PathVariable UUID id) {
        return scanService.getResults(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(404).body(new ErrorResponse("Scan not found: " + id)));
    }

    @PostMapping("/scan/{id}/cancel")
    public ResponseEntity<?> cancelScan(@PathVariable UUID id) {
        boolean cancelled = scanService.cancelScan(id);
        if (!cancelled) {
            return ResponseEntity.badRequest().body(
                    new ErrorResponse("Scan cannot be cancelled (not found or already finished)"));
        }
        return ResponseEntity.ok(Map.of("id", id, "status", ScanStatus.CANCELLED));
    }

    @DeleteMapping("/scan/{id}")
    public ResponseEntity<?> deleteScan(@PathVariable UUID id) {
        if (scanService.getStatus(id).isEmpty()) {
            return ResponseEntity.status(404).body(new ErrorResponse("Scan not found: " + id));
        }
        scanService.deleteScan(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/history")
    public ResponseEntity<PagedResponse<ScanStatusResponse>> getHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        size = Math.min(size, MAX_PAGE_SIZE);
        return ResponseEntity.ok(scanService.getHistory(page, size));
    }
}
