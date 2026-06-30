package com.netsentinel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ScanRequest(
        @NotBlank(message = "Target is required")
        @Size(max = 100, message = "Target cannot exceed 100 characters")
        String target,

        List<String> parameters,

        String language
) {}
