package com.netsentinel.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record AgentRequest(
        @NotBlank(message = "La API key es obligatoria")
        String apiKey,

        @NotEmpty(message = "Se requiere al menos un mensaje")
        @Valid
        List<Message> messages
) {
    public record Message(
            @NotBlank String role,
            @NotBlank String content
    ) {}
}
