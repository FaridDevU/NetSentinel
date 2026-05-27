package com.netsentinel.controller;

import com.netsentinel.dto.ErrorResponse;
import com.netsentinel.enums.VulnStatus;
import com.netsentinel.service.FindingStatusService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class FindingStatusController {

    private final FindingStatusService findingStatusService;

    public FindingStatusController(FindingStatusService findingStatusService) {
        this.findingStatusService = findingStatusService;
    }

    @GetMapping("/scan/{scanId}/findings/statuses")
    public ResponseEntity<Map<String, String>> getStatuses(@PathVariable UUID scanId) {
        return ResponseEntity.ok(findingStatusService.getStatuses(scanId));
    }

    @PutMapping("/scan/{scanId}/findings/status")
    public ResponseEntity<?> updateStatus(
            @PathVariable UUID scanId,
            @RequestBody StatusUpdateRequest request
    ) {
        if (request.findingKey() == null || request.findingKey().isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("findingKey es obligatorio"));
        }
        VulnStatus status;
        try {
            status = VulnStatus.valueOf(request.status());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Estado invalido: " + request.status()));
        }
        findingStatusService.updateStatus(scanId, request.findingKey(), status);
        return ResponseEntity.ok(Map.of("scanId", scanId, "findingKey", request.findingKey(), "status", status));
    }

    public record StatusUpdateRequest(String findingKey, String status) {}
}
