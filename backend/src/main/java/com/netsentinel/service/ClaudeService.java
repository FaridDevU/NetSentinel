package com.netsentinel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netsentinel.dto.AnalysisReport;
import com.netsentinel.dto.ScanResultsResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ClaudeService {

    private static final String ANTHROPIC_BASE = "https://api.anthropic.com";
    private static final String MODEL = "claude-opus-4-7";
    private static final int MAX_TOKENS = 4096;

    private final ObjectMapper objectMapper;

    public ClaudeService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String analyzeSecurityData(String apiKey, ScanResultsResponse scan) {
        String prompt = buildSecurityPrompt(scan);

        RestClient client = RestClient.builder()
                .baseUrl(ANTHROPIC_BASE)
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", MODEL);
        body.put("max_tokens", MAX_TOKENS);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));

        try {
            String response = client.post()
                    .uri("/v1/messages")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            return root.path("content").get(0).path("text").asText();

        } catch (HttpClientErrorException e) {
            String rawBody = e.getResponseBodyAsString();
            try {
                JsonNode err = objectMapper.readTree(rawBody);
                String msg = err.path("error").path("message").asText();
                throw new RuntimeException("Anthropic API error: " + (msg.isBlank() ? rawBody : msg), e);
            } catch (Exception ignored) {
                throw new RuntimeException("Anthropic API error " + e.getStatusCode() + ": " + rawBody, e);
            }
        } catch (Exception e) {
            throw new RuntimeException("Claude API call failed: " + e.getMessage(), e);
        }
    }

    private String buildSecurityPrompt(ScanResultsResponse scan) {
        StringBuilder sb = new StringBuilder();

        sb.append("You are a senior network security analyst reviewing scan results from NetSentinel, ");
        sb.append("an automated security auditing tool. Provide a deep analysis that goes BEYOND basic ");
        sb.append("vulnerability listing. Focus on: realistic attack paths between hosts, chained ");
        sb.append("vulnerability scenarios, what the combination of open services reveals about the ");
        sb.append("network architecture, exploitation likelihood given the specific context, and a ");
        sb.append("prioritized remediation roadmap with concrete steps.\n\n");

        sb.append("=== SCAN DATA ===\n");
        sb.append("Target: ").append(scan.target()).append("\n");
        sb.append("Status: ").append(scan.status()).append("\n");
        sb.append("Started: ").append(scan.startedAt()).append("\n");
        if (scan.completedAt() != null) {
            sb.append("Completed: ").append(scan.completedAt()).append("\n");
        }

        if (scan.analysis() != null) {
            AnalysisReport a = scan.analysis();
            sb.append("\n=== DETERMINISTIC ANALYSIS ===\n");
            sb.append("Risk Level: ").append(a.riskLevel())
              .append(" | Score: ").append(String.format("%.1f", a.riskScore())).append("/10\n");
            sb.append("Summary: ").append(a.summary()).append("\n");

            if (!a.findings().isEmpty()) {
                sb.append("\nFindings (").append(a.findings().size()).append("):\n");
                for (AnalysisReport.Finding f : a.findings()) {
                    sb.append("  [").append(f.severity()).append("] ").append(f.title())
                      .append(" — ").append(f.host()).append(":").append(f.port())
                      .append(" (").append(f.service()).append(")");
                    if (!f.relatedCves().isEmpty()) {
                        sb.append(" | CVEs: ").append(String.join(", ", f.relatedCves()));
                    }
                    sb.append("\n");
                }
            }

            if (!a.recommendations().isEmpty()) {
                sb.append("\nExisting recommendations:\n");
                a.recommendations().forEach(r -> sb.append("  - ").append(r).append("\n"));
            }
        }

        if (scan.hosts() != null && !scan.hosts().isEmpty()) {
            sb.append("\n=== HOST DETAILS ===\n");
            for (ScanResultsResponse.HostDto host : scan.hosts()) {
                sb.append("\nHost: ").append(host.ip());
                if (host.hostname() != null && !host.hostname().isBlank()) {
                    sb.append(" (").append(host.hostname()).append(")");
                }
                if (host.os() != null && !host.os().isBlank()) {
                    sb.append(" | OS: ").append(host.os());
                }
                if (host.vendor() != null && !host.vendor().isBlank()) {
                    sb.append(" | Vendor: ").append(host.vendor());
                }
                sb.append("\n");

                if (host.ports() != null) {
                    for (ScanResultsResponse.PortDto port : host.ports()) {
                        sb.append("  ").append(port.portNumber()).append("/").append(port.protocol())
                          .append(" [").append(port.state()).append("]");
                        if (port.service() != null) sb.append(" ").append(port.service());
                        if (port.version() != null && !port.version().isBlank()) {
                            sb.append(" ").append(port.version());
                        }
                        if (port.cves() != null && !port.cves().isEmpty()) {
                            sb.append(" | CVEs: ");
                            for (ScanResultsResponse.CveDto cve : port.cves()) {
                                sb.append(cve.cveId());
                                if (cve.cvssScore() != null) {
                                    sb.append("(").append(String.format("%.1f", cve.cvssScore())).append(")");
                                }
                                sb.append(" ");
                            }
                        }
                        sb.append("\n");
                    }
                }
            }
        }

        sb.append("\n=== ANALYSIS REQUESTED ===\n");
        sb.append("Respond with this exact structure:\n\n");
        sb.append("## Executive Summary\n");
        sb.append("(2-3 sentences on the overall security posture and most critical risk)\n\n");
        sb.append("## Attack Surface Analysis\n");
        sb.append("(What does the combination of exposed services reveal about this environment? ");
        sb.append("What is the likely role of each host?)\n\n");
        sb.append("## Realistic Attack Paths\n");
        sb.append("(Step-by-step attack scenarios an adversary could execute using discovered services and CVEs. ");
        sb.append("Be specific: which port, which CVE, which lateral movement technique)\n\n");
        sb.append("## Chained Vulnerability Scenarios\n");
        sb.append("(Combinations of individually lower-risk issues that together create a critical exposure)\n\n");
        sb.append("## Exploitation Likelihood Assessment\n");
        sb.append("(Given the specific versions, services, and network context — what is actually exploitable NOW vs theoretical)\n\n");
        sb.append("## Prioritized Remediation Roadmap\n");
        sb.append("(Numbered list, highest impact first. Include specific commands or config changes where possible)\n");

        return sb.toString();
    }
}
