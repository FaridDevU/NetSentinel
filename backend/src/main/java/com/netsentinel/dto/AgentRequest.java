package com.netsentinel.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record AgentRequest(
        @NotBlank(message = "API key is required")
        String apiKey,

        @NotEmpty(message = "At least one message is required")
        @Valid
        List<Message> messages
) {
    public record Message(
            @NotBlank String role,
            @NotBlank String content
    ) {}
}
