package com.netsentinel.dto;

import com.netsentinel.enums.ScanStatus;

import java.time.Instant;
import java.util.UUID;

public record ScanStatusResponse(
        UUID id,
        String target,
        ScanStatus status,
        Instant startedAt,
        Instant completedAt,
        String errorMessage
) {}
