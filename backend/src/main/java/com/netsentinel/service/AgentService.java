package com.netsentinel.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netsentinel.dto.AgentRequest;
import com.netsentinel.dto.ScanResultsResponse;
import com.netsentinel.dto.ScanStatusResponse;
import com.netsentinel.dto.PagedResponse;
import com.netsentinel.enums.ScanProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-sonnet-4-6";
    private static final int MAX_TOKENS = 4096;
    private static final int MAX_ITERATIONS = 15;

    private static final String SYSTEM_PROMPT = """
            You are NetSentinel, an expert network security consultant for non-technical users.
            Your goal is to help the user understand the security state of their home or
            small-business network, explain the findings in simple language, and guide them
            step by step to fix the problems found.

            You have tools to detect networks, launch scans, and read results.
            Always explain what you are doing before using a tool. When you get results,
            interpret them in clear language with no unnecessary jargon. If there are critical
            vulnerabilities, be direct about the real risk.

            Always respond in the user's language, defaulting to English. Be concise but complete.
            Do not use technical jargon without explaining it.
            """;

    private final ScanService scanService;
    private final NetworkService networkService;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    @Autowired
    public AgentService(ScanService scanService, NetworkService networkService, ObjectMapper objectMapper) {
        this(scanService, networkService, objectMapper, defaultRestClientBuilder());
    }

    AgentService(ScanService scanService, NetworkService networkService, ObjectMapper objectMapper,
                 RestClient.Builder restClientBuilder) {
        this.scanService = scanService;
        this.networkService = networkService;
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder.build();
    }

    private static RestClient.Builder defaultRestClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(15));
        factory.setReadTimeout(Duration.ofMinutes(5));
        return RestClient.builder().requestFactory(factory);
    }

    @Async("agentExecutor")
    public void streamChat(AgentRequest request, SseEmitter emitter) {
        try {
            List<Map<String, Object>> messages = buildMessages(request.messages());
            List<Map<String, Object>> systemBlocks = buildSystemBlocks();
            List<Map<String, Object>> tools = buildTools();

            for (int i = 0; i < MAX_ITERATIONS; i++) {
                Map<String, Object> requestBody = new LinkedHashMap<>();
                requestBody.put("model", MODEL);
                requestBody.put("max_tokens", MAX_TOKENS);
                requestBody.put("system", systemBlocks);
                requestBody.put("tools", tools);
                requestBody.put("messages", messages);

                String responseBody;
                try {
                    responseBody = restClient.post()
                            .uri(CLAUDE_API_URL)
                            .header("x-api-key", request.apiKey())
                            .header("anthropic-version", "2023-06-01")
                            .header("anthropic-beta", "prompt-caching-2024-07-31")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(requestBody)
                            .retrieve()
                            .body(String.class);
                } catch (HttpClientErrorException.Unauthorized e) {
                    emit(emitter, "error", Map.of("message", "Invalid API key or no access to the model"));
                    emitter.complete();
                    return;
                } catch (HttpClientErrorException.TooManyRequests e) {
                    emit(emitter, "error", Map.of("message", "Anthropic returned 429. Retry in a few seconds."));
                    emitter.complete();
                    return;
                } catch (HttpServerErrorException e) {
                    emit(emitter, "error", Map.of("message",
                            "Temporary Anthropic error (" + e.getStatusCode().value() + "). Retry later."));
                    emitter.complete();
                    return;
                } catch (ResourceAccessException e) {
                    emit(emitter, "error", Map.of("message",
                            "Could not reach Anthropic. Check your connection."));
                    emitter.complete();
                    return;
                }

                JsonNode response = objectMapper.readTree(responseBody);
                JsonNode content = response.path("content");
                String stopReason = response.path("stop_reason").asText("end_turn");
                if (!content.isArray()) {
                    emit(emitter, "error", Map.of("message", "Unexpected response from Anthropic"));
                    emitter.complete();
                    return;
                }

                List<Map<String, Object>> assistantContent = new ArrayList<>();
                List<Map<String, Object>> toolResults = new ArrayList<>();

                for (JsonNode block : content) {
                    String type = block.get("type").asText();

                    if ("text".equals(type)) {
                        String text = block.get("text").asText();
                        if (!text.isBlank()) {
                            emit(emitter, "text", Map.of("content", text));
                        }
                        Map<String, Object> textBlock = new LinkedHashMap<>();
                        textBlock.put("type", "text");
                        textBlock.put("text", text);
                        assistantContent.add(textBlock);

                    } else if ("tool_use".equals(type)) {
                        String toolId = block.get("id").asText();
                        String toolName = block.get("name").asText();
                        JsonNode toolInput = block.get("input");

                        emit(emitter, "tool_use", Map.of("name", toolName, "input", toolInput));

                        String toolResult = executeTool(toolName, toolInput);

                        emit(emitter, "tool_result", Map.of("name", toolName, "result", truncate(toolResult, 500)));

                        Map<String, Object> toolUseBlock = new LinkedHashMap<>();
                        toolUseBlock.put("type", "tool_use");
                        toolUseBlock.put("id", toolId);
                        toolUseBlock.put("name", toolName);
                        toolUseBlock.put("input", toolInput);
                        assistantContent.add(toolUseBlock);

                        Map<String, Object> resultBlock = new LinkedHashMap<>();
                        resultBlock.put("type", "tool_result");
                        resultBlock.put("tool_use_id", toolId);
                        resultBlock.put("content", toolResult);
                        toolResults.add(resultBlock);
                    }
                }

                Map<String, Object> assistantMsg = new LinkedHashMap<>();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", assistantContent);
                messages.add(assistantMsg);

                if (!"tool_use".equals(stopReason) || toolResults.isEmpty()) {
                    break;
                }

                Map<String, Object> userMsg = new LinkedHashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", toolResults);
                messages.add(userMsg);
            }

            emit(emitter, "done", Map.of());
            emitter.complete();

        } catch (Exception e) {
            log.error("Agent stream error", e);
            try {
                emit(emitter, "error", Map.of("message", e.getMessage() != null ? e.getMessage() : "Unknown error"));
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
    }

    private List<Map<String, Object>> buildMessages(List<AgentRequest.Message> messages) {
        return messages.stream().map(msg -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", msg.role());
            m.put("content", msg.content());
            return m;
        }).collect(Collectors.toCollection(ArrayList::new));
    }

    @SuppressWarnings("unchecked")
    private String executeTool(String name, JsonNode input) {
        try {
            return switch (name) {
                case "detect_networks" -> {
                    List<Map<String, String>> networks = networkService.getLocalNetworks();
                    yield objectMapper.writeValueAsString(networks);
                }
                case "start_scan" -> {
                    String target = input.get("target").asText();
                    List<String> params = input.has("parameters")
                            ? objectMapper.convertValue(input.get("parameters"), new TypeReference<>() {})
                            : List.of("-sV", "-T4");
                    if (!ScanProfile.isAllowed(params)) {
                        params = ScanProfile.ESTANDAR.parameters();
                    }
                    var job = scanService.createScan(target, params);
                    scanService.executeScan(job.getId());
                    Map<String, Object> started = new LinkedHashMap<>();
                    started.put("scanId", job.getId().toString());
                    started.put("target", job.getTarget());
                    started.put("status", job.getStatus().name());
                    yield objectMapper.writeValueAsString(started);
                }
                case "get_scan_status" -> {
                    UUID id = UUID.fromString(input.get("scanId").asText());
                    var status = scanService.getStatus(id);
                    if (status.isEmpty()) yield "{\"error\":\"Scan not found\"}";
                    yield objectMapper.writeValueAsString(status.get());
                }
                case "get_scan_results" -> {
                    UUID id = UUID.fromString(input.get("scanId").asText());
                    var results = scanService.getResults(id);
                    if (results.isEmpty()) yield "{\"error\":\"Scan not found\"}";
                    yield objectMapper.writeValueAsString(condense(results.get()));
                }
                case "get_scan_logs" -> {
                    UUID id = UUID.fromString(input.get("scanId").asText());
                    List<String> logs = scanService.getLogs(id);
                    yield objectMapper.writeValueAsString(Map.of("lines", logs));
                }
                case "get_history" -> {
                    int page = input.has("page") ? input.get("page").asInt(0) : 0;
                    PagedResponse<ScanStatusResponse> history = scanService.getHistory(page, 10);
                    yield objectMapper.writeValueAsString(history);
                }
                default -> "{\"error\":\"Unknown tool: " + name + "\"}";
            };
        } catch (Exception e) {
            log.error("Tool execution error for {}: {}", name, e.getMessage());
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private Map<String, Object> condense(ScanResultsResponse full) {
        List<Map<String, Object>> hosts = full.hosts().stream().map(host -> {
            List<Map<String, Object>> ports = host.ports().stream()
                    .filter(p -> "open".equals(p.state()))
                    .map(port -> {
                        List<Map<String, Object>> topCves = port.cves().stream()
                                .sorted(Comparator.comparingDouble(
                                        (ScanResultsResponse.CveDto c) -> c.cvssScore() != null ? c.cvssScore() : 0.0
                                ).reversed())
                                .limit(3)
                                .map(cve -> {
                                    Map<String, Object> m = new LinkedHashMap<>();
                                    m.put("cveId", cve.cveId());
                                    m.put("description", truncate(cve.description(), 150));
                                    m.put("cvssScore", cve.cvssScore() != null ? cve.cvssScore() : 0.0);
                                    return m;
                                })
                                .collect(Collectors.toList());

                        Map<String, Object> portMap = new LinkedHashMap<>();
                        portMap.put("port", port.portNumber());
                        portMap.put("service", port.service() != null ? port.service() : "");
                        portMap.put("version", port.version() != null ? port.version() : "");
                        portMap.put("cveCount", port.cves().size());
                        portMap.put("topCves", topCves);
                        return portMap;
                    })
                    .collect(Collectors.toList());

            List<Map<String, Object>> webFindings = host.webFindings().stream()
                    .map(wf -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("tool", wf.tool());
                        m.put("url", wf.url());
                        m.put("severity", wf.severity() != null ? wf.severity() : "INFO");
                        m.put("description", truncate(wf.description(), 150));
                        return m;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> hostMap = new LinkedHashMap<>();
            hostMap.put("ip", host.ip());
            hostMap.put("hostname", host.hostname() != null ? host.hostname() : "");
            hostMap.put("os", host.os() != null ? host.os() : "");
            hostMap.put("ports", ports);
            hostMap.put("webFindings", webFindings);
            return hostMap;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", full.target());
        result.put("status", full.status().name());
        result.put("hosts", hosts);
        if (full.analysis() != null) {
            result.put("riskLevel", full.analysis().riskLevel());
            result.put("riskScore", full.analysis().riskScore());
            result.put("summary", full.analysis().summary());
            result.put("recommendations", full.analysis().recommendations());
        }
        return result;
    }

    private List<Map<String, Object>> buildSystemBlocks() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", SYSTEM_PROMPT);
        block.put("cache_control", Map.of("type", "ephemeral"));
        return List.of(block);
    }

    private List<Map<String, Object>> buildTools() {
        Map<String, Object> detectNetworks = new LinkedHashMap<>();
        detectNetworks.put("name", "detect_networks");
        detectNetworks.put("description", "Detects the local network interfaces and their subnets. Use it when you need to know which networks are available to scan.");
        Map<String, Object> detectSchema = new LinkedHashMap<>();
        detectSchema.put("type", "object");
        detectSchema.put("properties", Map.of());
        detectSchema.put("required", List.of());
        detectNetworks.put("input_schema", detectSchema);

        Map<String, Object> targetProp = new LinkedHashMap<>();
        targetProp.put("type", "string");
        targetProp.put("description", "IP, CIDR range or hostname to scan");
        Map<String, Object> paramItemsProp = new LinkedHashMap<>();
        paramItemsProp.put("type", "string");
        Map<String, Object> paramsProp = new LinkedHashMap<>();
        paramsProp.put("type", "array");
        paramsProp.put("items", paramItemsProp);
        paramsProp.put("description", "nmap flags. Default: [\"-sV\", \"-T4\"]");
        Map<String, Object> startScanProps = new LinkedHashMap<>();
        startScanProps.put("target", targetProp);
        startScanProps.put("parameters", paramsProp);
        Map<String, Object> startScan = new LinkedHashMap<>();
        startScan.put("name", "start_scan");
        startScan.put("description", "Launches a network scan against a target. Returns the scanId to track progress.");
        Map<String, Object> startScanSchema = new LinkedHashMap<>();
        startScanSchema.put("type", "object");
        startScanSchema.put("properties", startScanProps);
        startScanSchema.put("required", List.of("target"));
        startScan.put("input_schema", startScanSchema);

        Map<String, Object> scanIdProp = new LinkedHashMap<>();
        scanIdProp.put("type", "string");
        scanIdProp.put("description", "UUID of the scan");
        Map<String, Object> scanIdProps = new LinkedHashMap<>();
        scanIdProps.put("scanId", scanIdProp);

        Map<String, Object> getStatus = new LinkedHashMap<>();
        getStatus.put("name", "get_scan_status");
        getStatus.put("description", "Queries the current status of a scan by its ID.");
        Map<String, Object> statusSchema = new LinkedHashMap<>();
        statusSchema.put("type", "object");
        statusSchema.put("properties", scanIdProps);
        statusSchema.put("required", List.of("scanId"));
        getStatus.put("input_schema", statusSchema);

        Map<String, Object> getResults = new LinkedHashMap<>();
        getResults.put("name", "get_scan_results");
        getResults.put("description", "Retrieves the full results of a completed scan: hosts, ports, CVEs and web findings.");
        Map<String, Object> resultsSchema = new LinkedHashMap<>();
        resultsSchema.put("type", "object");
        resultsSchema.put("properties", scanIdProps);
        resultsSchema.put("required", List.of("scanId"));
        getResults.put("input_schema", resultsSchema);

        Map<String, Object> getLogs = new LinkedHashMap<>();
        getLogs.put("name", "get_scan_logs");
        getLogs.put("description", "Retrieves the progress logs of a scan.");
        Map<String, Object> logsSchema = new LinkedHashMap<>();
        logsSchema.put("type", "object");
        logsSchema.put("properties", scanIdProps);
        logsSchema.put("required", List.of("scanId"));
        getLogs.put("input_schema", logsSchema);

        Map<String, Object> pageProp = new LinkedHashMap<>();
        pageProp.put("type", "integer");
        pageProp.put("description", "Page number (0-indexed). Default: 0");
        Map<String, Object> historyProps = new LinkedHashMap<>();
        historyProps.put("page", pageProp);
        Map<String, Object> getHistory = new LinkedHashMap<>();
        getHistory.put("name", "get_history");
        getHistory.put("description", "Retrieves the history of previous scans.");
        Map<String, Object> historySchema = new LinkedHashMap<>();
        historySchema.put("type", "object");
        historySchema.put("properties", historyProps);
        historySchema.put("required", List.of());
        getHistory.put("input_schema", historySchema);
        getHistory.put("cache_control", Map.of("type", "ephemeral"));

        return List.of(detectNetworks, startScan, getStatus, getResults, getLogs, getHistory);
    }

    private void emit(SseEmitter emitter, String eventType, Object data) throws Exception {
        String json = objectMapper.writeValueAsString(data);
        emitter.send(SseEmitter.event().name(eventType).data(json));
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
