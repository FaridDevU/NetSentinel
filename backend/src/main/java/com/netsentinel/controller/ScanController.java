package com.netsentinel.controller;

import com.netsentinel.dto.ErrorResponse;
import com.netsentinel.dto.PagedResponse;
import com.netsentinel.dto.ScanCompareResponse;
import com.netsentinel.dto.ScanRequest;
import com.netsentinel.dto.ScanResultsResponse;
import com.netsentinel.dto.ScanStatusResponse;
import com.netsentinel.entity.ScanJob;
import com.netsentinel.enums.ScanStatus;
import com.netsentinel.service.ExportService;
import com.netsentinel.service.ScanCompareService;
import com.netsentinel.service.ScanService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import java.util.UUID;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ScanController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final java.util.regex.Pattern VALID_TARGET =
            java.util.regex.Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9.\\-:/\\[\\]]{0,99}$");

    private final ScanService scanService;
    private final ExportService exportService;
    private final ScanCompareService compareService;

    public ScanController(ScanService scanService, ExportService exportService, ScanCompareService compareService) {
        this.scanService = scanService;
        this.exportService = exportService;
        this.compareService = compareService;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "ok"));
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

    @GetMapping("/scan/{id}/logs")
    public ResponseEntity<?> getScanLogs(@PathVariable UUID id) {
        return ResponseEntity.ok(Map.of("lines", scanService.getLogs(id)));
    }

    @GetMapping(value = "/scan/{id}/export/pdf", produces = "application/pdf")
    public ResponseEntity<?> exportPdf(@PathVariable UUID id) {
        var result = scanService.getResults(id);
        if (result.isEmpty()) return ResponseEntity.status(404).body(new ErrorResponse("Scan no encontrado: " + id));
        ScanResultsResponse scan = result.get();
        try {
            byte[] pdf = exportService.generatePdf(scan, scan.analysis());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"netsentinel-report-" + id + ".pdf\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdf);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(new ErrorResponse("Error generando PDF: " + e.getMessage()));
        }
    }

    @GetMapping(value = "/scan/{id}/export/json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> exportJson(@PathVariable UUID id) {
        var result = scanService.getResults(id);
        if (result.isEmpty()) return ResponseEntity.status(404).body(new ErrorResponse("Scan no encontrado: " + id));
        try {
            byte[] json = exportService.generateJson(result.get());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"netsentinel-report-" + id + ".json\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(new ErrorResponse("Error generando JSON: " + e.getMessage()));
        }
    }

    @GetMapping(value = "/scan/{id}/export/csv", produces = "text/csv")
    public ResponseEntity<?> exportCsv(@PathVariable UUID id) {
        var result = scanService.getResults(id);
        if (result.isEmpty()) return ResponseEntity.status(404).body(new ErrorResponse("Scan no encontrado: " + id));
        ScanResultsResponse scan = result.get();
        byte[] csv = exportService.generateCsv(scan, scan.analysis());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"netsentinel-report-" + id + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(csv);
    }

    @GetMapping("/scan/compare")
    public ResponseEntity<?> compareScan(
            @RequestParam UUID a,
            @RequestParam UUID b) {
        if (a.equals(b)) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Los dos IDs deben ser distintos"));
        }
        try {
            ScanCompareResponse compare = compareService.compare(a, b);
            return ResponseEntity.ok(compare);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(new ErrorResponse(e.getMessage()));
        }
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
                    if (ip.startsWith("169.254")) continue;

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
