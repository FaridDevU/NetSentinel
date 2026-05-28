package com.netsentinel.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Component
public class SandboxHealthIndicator implements HealthIndicator {

    private final RestClient restClient;
    private final String sandboxUrl;

    public SandboxHealthIndicator(@Value("${sandbox.url:http://127.0.0.1:7878}") String sandboxUrl) {
        this.sandboxUrl = sandboxUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.restClient = RestClient.builder()
                .baseUrl(sandboxUrl)
                .requestFactory(factory)
                .build();
    }

    public boolean isReachable() {
        try {
            restClient.get().uri("/health").retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Health health() {
        if (isReachable()) {
            return Health.up().withDetail("url", sandboxUrl).build();
        }
        return Health.down().withDetail("url", sandboxUrl).build();
    }
}
