package com.netsentinel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ScanRequest(
        @NotBlank(message = "El objetivo es obligatorio")
        @Size(max = 100, message = "El objetivo no puede superar 100 caracteres")
        String target,

        List<String> parameters
) {}
