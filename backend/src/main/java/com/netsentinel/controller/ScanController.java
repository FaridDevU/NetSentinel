package com.netsentinel.controller;

import com.netsentinel.dto.AiReportRequest;
import com.netsentinel.dto.ErrorResponse;
import com.netsentinel.dto.PagedResponse;
import com.netsentinel.dto.ScanRequest;
import com.netsentinel.dto.ScanResultsResponse;
import com.netsentinel.dto.ScanStatusResponse;
import com.netsentinel.entity.ScanJob;
import com.netsentinel.enums.ScanStatus;
import com.netsentinel.service.ClaudeService;
import com.netsentinel.service.ScanService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.Inet4Address;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ScanController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final java.util.regex.Pattern VALID_TARGET =
            java.util.regex.Pattern.compile("^[a-zA-Z0-9.\\-:/\\[\\]]{1,100}$");

    private final ScanService scanService;
    private final ClaudeService claudeService;

    public ScanController(ScanService scanService, ClaudeService claudeService) {
        this.scanService = scanService;
        this.claudeService = claudeService;
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

    @PostMapping("/scan/{id}/ai-report")
    public ResponseEntity<?> generateAiReport(@PathVariable UUID id, @RequestBody AiReportRequest request) {
        if (request.apiKey() == null || request.apiKey().isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("API key is required"));
        }

        Optional<ScanResultsResponse> results = scanService.getResults(id);
        if (results.isEmpty()) {
            return ResponseEntity.status(404).body(new ErrorResponse("Scan not found: " + id));
        }
        if (results.get().status() != ScanStatus.COMPLETED) {
            return ResponseEntity.badRequest().body(
                    new ErrorResponse("Scan must be COMPLETED to generate AI report"));
        }

        try {
            String report = claudeService.analyzeSecurityData(request.apiKey(), results.get());
            return ResponseEntity.ok(Map.of("report", report));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(new ErrorResponse(e.getMessage()));
        }
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

    @GetMapping("/scan/{id}/logs")
    public ResponseEntity<?> getScanLogs(@PathVariable UUID id) {
        return ResponseEntity.ok(Map.of("lines", scanService.getLogs(id)));
    }

    @GetMapping("/network/local")
    public ResponseEntity<?> getLocalNetworks() {
        try {
            List<Map<String, String>> result = new ArrayList<>();
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();

            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;

                String displayName = ni.getDisplayName().toLowerCase();
                if (displayName.contains("hyper-v") || displayName.contains("wsl")
                        || displayName.contains("virtual") || displayName.contains("tunnel")
                        || displayName.contains("loopback") || displayName.contains("pseudo")) {
                    continue;
                }

                for (InterfaceAddress addr : ni.getInterfaceAddresses()) {
                    if (!(addr.getAddress() instanceof Inet4Address)) continue;
                    String ip = addr.getAddress().getHostAddress();
                    if (ip.startsWith("169.254")) continue; // link-local, skip

                    int prefixLen = addr.getNetworkPrefixLength();
                    String subnet = calculateSubnet(ip, prefixLen);

                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("name", ni.getDisplayName());
                    entry.put("ip", ip);
                    entry.put("subnet", subnet + "/" + prefixLen);
                    result.add(entry);
                }
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(new ErrorResponse("Failed to detect network interfaces"));
        }
    }

    private String calculateSubnet(String ip, int prefixLen) {
        String[] parts = ip.split("\\.");
        int ipInt = (Integer.parseInt(parts[0]) << 24)
                  | (Integer.parseInt(parts[1]) << 16)
                  | (Integer.parseInt(parts[2]) << 8)
                  | Integer.parseInt(parts[3]);
        int mask = prefixLen == 0 ? 0 : (0xFFFFFFFF << (32 - prefixLen));
        int network = ipInt & mask;
        return ((network >> 24) & 0xFF) + "."
             + ((network >> 16) & 0xFF) + "."
             + ((network >> 8) & 0xFF) + "."
             + (network & 0xFF);
    }
}
