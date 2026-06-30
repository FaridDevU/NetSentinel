package com.netsentinel.controller;

import com.netsentinel.dto.AgentRequest;
import com.netsentinel.exception.ApiException;
import com.netsentinel.exception.ErrorCode;
import com.netsentinel.service.AgentService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody AgentRequest request) {
        if (request.apiKey() == null || request.apiKey().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "API key is required");
        }
        SseEmitter emitter = new SseEmitter(600_000L);
        agentService.streamChat(request, emitter);
        return emitter;
    }
}
