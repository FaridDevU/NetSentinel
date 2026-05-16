package com.netsentinel.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class SandboxService {

    private static final Logger log = LoggerFactory.getLogger(SandboxService.class);

    private final RestClient restClient;

    public SandboxService(@Value("${sandbox.url:http://127.0.0.1:7878}") String sandboxUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofMinutes(11));
        this.restClient = RestClient.builder()
                .baseUrl(sandboxUrl)
                .requestFactory(factory)
                .build();
    }

    public record SandboxResult(boolean success, String output, String error) {}

    public SandboxResult runNmap(String target, List<String> parameters) {
        List<String> args = new ArrayList<>(parameters);
        args.add("-oX");
        args.add("-");
        return execute("nmap", target, args, 600);
    }

    public SandboxResult runGobuster(String target, List<String> parameters) {
        return execute("gobuster", target, parameters, 300);
    }

    public SandboxResult runNikto(String target, List<String> parameters) {
        return execute("nikto", target, parameters, 300);
    }

    private SandboxResult execute(String tool, String target, List<String> args, int timeoutSecs) {
        log.info("Calling sandbox: tool={} target={}", tool, target);

        Map<String, Object> requestBody = Map.of(
                "tool", tool,
                "target", target,
                "args", args,
                "timeout_secs", timeoutSecs
        );

        try {
            SandboxResponse response = restClient.post()
                    .uri("/execute")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
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
