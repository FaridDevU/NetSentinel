package com.netsentinel.dto;

import java.util.List;

public record AgentRequest(
        String apiKey,
        List<Message> messages
) {
    public record Message(String role, String content) {}
}
