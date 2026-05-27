package com.netsentinel.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SandboxService {

    private static final Logger log = LoggerFactory.getLogger(SandboxService.class);
    private static final String AUTH_HEADER = "X-Sandbox-Auth";

    private final RestClient restClient;
    private final String authToken;

    public SandboxService(@Value("${sandbox.url:http://127.0.0.1:7878}") String sandboxUrl,
                          @Value("${sandbox.auth-token:}") String authToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofMinutes(11));
        this.restClient = RestClient.builder()
                .baseUrl(sandboxUrl)
                .requestFactory(factory)
                .build();
        if (authToken == null || authToken.isBlank()) {
            this.authToken = "dev-token-changeme";
            log.warn("sandbox.auth-token vacio, usando token de desarrollo. Configurar SANDBOX_AUTH_TOKEN en produccion.");
        } else {
            this.authToken = authToken;
        }
    }

    public record SandboxResult(boolean success, String output, String error) {}

    public SandboxResult runNmap(UUID executionId, String target, List<String> parameters) {
        List<String> args = new ArrayList<>(parameters);
        args.add("-oX");
        args.add("-");
        return execute(executionId, "nmap", target, args, 600);
    }

    public SandboxResult runGobuster(UUID executionId, String url) {
        List<String> args = List.of(
                "dir",
                "-w", "/usr/share/wordlists/dirb/common.txt",
                "-t", "50",
                "--timeout", "5s",
                "-q",
                "-u"
        );
        return execute(executionId, "gobuster", url, args, 120);
    }

    public SandboxResult runNikto(UUID executionId, String url) {
        List<String> args = List.of("-maxtime", "60", "-h");
        return execute(executionId, "nikto", url, args, 90);
    }

    public void cancelExecution(UUID executionId) {
        try {
            restClient.post()
                    .uri("/cancel/{id}", executionId.toString())
                    .header(AUTH_HEADER, authToken)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.debug("Could not cancel sandbox execution {}: {}", executionId, e.getMessage());
        }
    }

    private SandboxResult execute(UUID executionId, String tool, String target, List<String> args, int timeoutSecs) {
        log.info("Calling sandbox: tool={} target={}", tool, target);

        Map<String, Object> requestBody = Map.of(
                "tool", tool,
                "target", target,
                "args", args,
                "timeout_secs", timeoutSecs,
                "execution_id", executionId.toString()
        );

        try {
            SandboxResponse response = restClient.post()
                    .uri("/execute")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(AUTH_HEADER, authToken)
                    .body(requestBody)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        if (res.getStatusCode().value() == 401) {
                            throw new IllegalStateException("Sandbox rechazo el token de autenticacion");
                        }
                    })
                    .body(SandboxResponse.class);

            if (response == null) {
                return new SandboxResult(false, null, "Sandbox returned empty response");
            }

            if (response.success()) {
                log.info("Sandbox execution completed in {}ms", response.durationMs());
                return new SandboxResult(true, response.stdout(), null);
            } else {
                String error = response.error() != null ? response.error() : response.stderr();
                log.warn("Sandbox execution failed: {}", error);
                return new SandboxResult(false, null, error);
            }

        } catch (ResourceAccessException e) {
            String msg = "Sandbox is not reachable at configured URL. Is the Rust sandbox running?";
            log.error(msg, e);
            return new SandboxResult(false, null, msg);
        } catch (Exception e) {
            log.error("Sandbox call failed", e);
            return new SandboxResult(false, null, "Sandbox error: " + e.getMessage());
        }
    }

    private record SandboxResponse(
            boolean success,
            String stdout,
            String stderr,
            Integer exitCode,
            long durationMs,
            String error
    ) {}
}
