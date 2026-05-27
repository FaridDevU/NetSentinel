package com.netsentinel.controller;

import com.netsentinel.dto.PagedResponse;
import com.netsentinel.dto.ScanCompareResponse;
import com.netsentinel.dto.ScanRequest;
import com.netsentinel.dto.ScanResultsResponse;
import com.netsentinel.dto.ScanStatusResponse;
import com.netsentinel.entity.ScanJob;
import com.netsentinel.enums.ScanProfile;
import com.netsentinel.enums.ScanStatus;
import com.netsentinel.exception.ApiException;
import com.netsentinel.exception.ErrorCode;
import com.netsentinel.service.ExportService;
import com.netsentinel.service.NetworkService;
import com.netsentinel.service.ScanCompareService;
import com.netsentinel.service.ScanService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ScanController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final java.util.regex.Pattern VALID_TARGET =
            java.util.regex.Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9.\\-:/\\[\\]]{0,99}$");

    private final ScanService scanService;
    private final ExportService exportService;
    private final ScanCompareService compareService;
    private final NetworkService networkService;

    public ScanController(ScanService scanService, ExportService exportService,
                          ScanCompareService compareService, NetworkService networkService) {
        this.scanService = scanService;
        this.exportService = exportService;
        this.compareService = compareService;
        this.networkService = networkService;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/scan/start")
    public ResponseEntity<?> startScan(@Valid @RequestBody ScanRequest request) {
        if (request.target() == null || request.target().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_TARGET, "El objetivo es obligatorio");
        }

        String target = request.target().trim();
        if (!VALID_TARGET.matcher(target).matches()) {
            throw new ApiException(ErrorCode.INVALID_TARGET,
                    "Formato de objetivo invalido. Aceptado: IP, CIDR o hostname sin caracteres especiales");
        }

        List<String> parameters = request.parameters() != null
                ? request.parameters()
                : ScanProfile.ESTANDAR.parameters();
        if (!ScanProfile.isAllowed(parameters)) {
            throw new ApiException(ErrorCode.INVALID_SCAN_PROFILE,
                    "Perfil de escaneo invalido. Aceptados: RAPIDO, ESTANDAR, COMPLETO");
        }

        ScanJob job = scanService.createScan(target, parameters);
        scanService.executeScan(job.getId());

        return ResponseEntity.accepted().body(Map.of(
                "id", job.getId(),
                "target", job.getTarget(),
                "status", job.getStatus()
        ));
    }

    @GetMapping("/scan/{id}/status")
    public ResponseEntity<ScanStatusResponse> getScanStatus(@PathVariable UUID id) {
        return ResponseEntity.ok(scanService.getStatus(id)
                .orElseThrow(() -> new ApiException(ErrorCode.SCAN_NOT_FOUND, "Scan no encontrado: " + id)));
    }

    @GetMapping("/scan/{id}/results")
    public ResponseEntity<ScanResultsResponse> getScanResults(@PathVariable UUID id) {
        return ResponseEntity.ok(scanService.getResults(id)
                .orElseThrow(() -> new ApiException(ErrorCode.SCAN_NOT_FOUND, "Scan no encontrado: " + id)));
    }

    @PostMapping("/scan/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancelScan(@PathVariable UUID id) {
        boolean cancelled = scanService.cancelScan(id);
        if (!cancelled) {
            throw new ApiException(ErrorCode.SCAN_NOT_CANCELLABLE,
                    "El escaneo no puede cancelarse (no encontrado o ya finalizado)");
        }
        return ResponseEntity.ok(Map.of("id", id, "status", ScanStatus.CANCELLED));
    }

    @DeleteMapping("/scan/{id}")
    public ResponseEntity<Void> deleteScan(@PathVariable UUID id) {
        if (scanService.getStatus(id).isEmpty()) {
            throw new ApiException(ErrorCode.SCAN_NOT_FOUND, "Scan no encontrado: " + id);
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
    public ResponseEntity<Map<String, Object>> getScanLogs(@PathVariable UUID id) {
        return ResponseEntity.ok(Map.of("lines", scanService.getLogs(id)));
    }

    @GetMapping(value = "/scan/{id}/export/pdf", produces = "application/pdf")
    public ResponseEntity<byte[]> exportPdf(@PathVariable UUID id) {
        ScanResultsResponse scan = scanService.getResults(id)
                .orElseThrow(() -> new ApiException(ErrorCode.SCAN_NOT_FOUND, "Scan no encontrado: " + id));
        try {
            byte[] pdf = exportService.generatePdf(scan, scan.analysis());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"netsentinel-report-" + id + ".pdf\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdf);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.EXPORT_FAILED, "Error generando PDF: " + e.getMessage(), e);
        }
    }

    @GetMapping(value = "/scan/{id}/export/json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> exportJson(@PathVariable UUID id) {
        ScanResultsResponse scan = scanService.getResults(id)
                .orElseThrow(() -> new ApiException(ErrorCode.SCAN_NOT_FOUND, "Scan no encontrado: " + id));
        try {
            byte[] json = exportService.generateJson(scan);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"netsentinel-report-" + id + ".json\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.EXPORT_FAILED, "Error generando JSON: " + e.getMessage(), e);
        }
    }

    @GetMapping(value = "/scan/{id}/export/csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportCsv(@PathVariable UUID id) {
        ScanResultsResponse scan = scanService.getResults(id)
                .orElseThrow(() -> new ApiException(ErrorCode.SCAN_NOT_FOUND, "Scan no encontrado: " + id));
        byte[] csv = exportService.generateCsv(scan, scan.analysis());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"netsentinel-report-" + id + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(csv);
    }

    @GetMapping("/scan/compare")
    public ResponseEntity<ScanCompareResponse> compareScan(
            @RequestParam UUID a,
            @RequestParam UUID b) {
        if (a.equals(b)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Los dos IDs deben ser distintos");
        }
        try {
            return ResponseEntity.ok(compareService.compare(a, b));
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.SCAN_NOT_FOUND, e.getMessage(), e);
        }
    }

    @GetMapping("/network/local")
    public ResponseEntity<List<Map<String, String>>> getLocalNetworks() {
        try {
            return ResponseEntity.ok(networkService.getLocalNetworks());
        } catch (Exception e) {
            throw new ApiException(ErrorCode.NETWORK_DETECTION_FAILED,
                    "No se pudieron detectar las interfaces de red", e);
        }
    }
}
