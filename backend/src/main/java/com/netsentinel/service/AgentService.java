package com.netsentinel.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netsentinel.dto.AgentRequest;
import com.netsentinel.dto.ScanResultsResponse;
import com.netsentinel.dto.ScanStatusResponse;
import com.netsentinel.dto.PagedResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
            Eres NetSentinel, un consultor experto en seguridad de redes para usuarios no técnicos.
            Tu objetivo es ayudar al usuario a entender el estado de seguridad de su red doméstica o
            de pequeña empresa, explicar los hallazgos en lenguaje simple, y guiarle paso a paso para
            resolver los problemas encontrados.

            Tienes acceso a herramientas para detectar redes, lanzar escaneos, y leer resultados.
            Siempre explica qué estás haciendo antes de usar una herramienta. Cuando obtengas resultados,
            interprétalos en lenguaje claro y sin tecnicismos innecesarios. Si hay vulnerabilidades críticas,
            sé directo sobre el riesgo real.

            Responde siempre en español. Sé conciso pero completo. No uses jerga técnica sin explicarla.
            """;

    private final ScanService scanService;
    private final NetworkService networkService;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public AgentService(ScanService scanService, NetworkService networkService, ObjectMapper objectMapper) {
        this.scanService = scanService;
        this.networkService = networkService;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create();
    }

    @Async("scanExecutor")
    public void streamChat(AgentRequest request, SseEmitter emitter) {
        try {
            List<Map<String, Object>> messages = buildMessages(request.messages());

            for (int i = 0; i < MAX_ITERATIONS; i++) {
                Map<String, Object> requestBody = new LinkedHashMap<>();
                requestBody.put("model", MODEL);
                requestBody.put("max_tokens", MAX_TOKENS);
                requestBody.put("system", SYSTEM_PROMPT);
                requestBody.put("tools", buildTools());
                requestBody.put("messages", messages);

                String responseBody = restClient.post()
                        .uri(CLAUDE_API_URL)
                        .header("x-api-key", request.apiKey())
                        .header("anthropic-version", "2023-06-01")
                        .header("content-type", "application/json")
                        .body(requestBody)
                        .retrieve()
                        .body(String.class);

                JsonNode response = objectMapper.readTree(responseBody);
                JsonNode content = response.get("content");
                String stopReason = response.get("stop_reason").asText();

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
                emit(emitter, "error", Map.of("message", e.getMessage() != null ? e.getMessage() : "Error desconocido"));
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
                    if (status.isEmpty()) yield "{\"error\":\"Scan no encontrado\"}";
                    yield objectMapper.writeValueAsString(status.get());
                }
                case "get_scan_results" -> {
                    UUID id = UUID.fromString(input.get("scanId").asText());
                    var results = scanService.getResults(id);
                    if (results.isEmpty()) yield "{\"error\":\"Scan no encontrado\"}";
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
                default -> "{\"error\":\"Herramienta desconocida: " + name + "\"}";
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

    private List<Map<String, Object>> buildTools() {
        Map<String, Object> detectNetworks = new LinkedHashMap<>();
        detectNetworks.put("name", "detect_networks");
        detectNetworks.put("description", "Detecta las interfaces de red locales y sus subredes. Úsala cuando necesites saber qué redes están disponibles para escanear.");
        Map<String, Object> detectSchema = new LinkedHashMap<>();
        detectSchema.put("type", "object");
        detectSchema.put("properties", Map.of());
        detectSchema.put("required", List.of());
        detectNetworks.put("input_schema", detectSchema);

        Map<String, Object> targetProp = new LinkedHashMap<>();
        targetProp.put("type", "string");
        targetProp.put("description", "IP, rango CIDR o hostname a escanear");
        Map<String, Object> paramItemsProp = new LinkedHashMap<>();
        paramItemsProp.put("type", "string");
        Map<String, Object> paramsProp = new LinkedHashMap<>();
        paramsProp.put("type", "array");
        paramsProp.put("items", paramItemsProp);
        paramsProp.put("description", "Flags de nmap. Por defecto: [\"-sV\", \"-T4\"]");
        Map<String, Object> startScanProps = new LinkedHashMap<>();
        startScanProps.put("target", targetProp);
        startScanProps.put("parameters", paramsProp);
        Map<String, Object> startScan = new LinkedHashMap<>();
        startScan.put("name", "start_scan");
        startScan.put("description", "Lanza un escaneo de red contra un objetivo. Devuelve el scanId para seguir el progreso.");
        Map<String, Object> startScanSchema = new LinkedHashMap<>();
        startScanSchema.put("type", "object");
        startScanSchema.put("properties", startScanProps);
        startScanSchema.put("required", List.of("target"));
        startScan.put("input_schema", startScanSchema);

        Map<String, Object> scanIdProp = new LinkedHashMap<>();
        scanIdProp.put("type", "string");
        scanIdProp.put("description", "UUID del escaneo");
        Map<String, Object> scanIdProps = new LinkedHashMap<>();
        scanIdProps.put("scanId", scanIdProp);

        Map<String, Object> getStatus = new LinkedHashMap<>();
        getStatus.put("name", "get_scan_status");
        getStatus.put("description", "Consulta el estado actual de un escaneo por su ID.");
        Map<String, Object> statusSchema = new LinkedHashMap<>();
        statusSchema.put("type", "object");
        statusSchema.put("properties", scanIdProps);
        statusSchema.put("required", List.of("scanId"));
        getStatus.put("input_schema", statusSchema);

        Map<String, Object> getResults = new LinkedHashMap<>();
        getResults.put("name", "get_scan_results");
        getResults.put("description", "Obtiene los resultados completos de un escaneo finalizado: hosts, puertos, CVEs y hallazgos web.");
        Map<String, Object> resultsSchema = new LinkedHashMap<>();
        resultsSchema.put("type", "object");
        resultsSchema.put("properties", scanIdProps);
        resultsSchema.put("required", List.of("scanId"));
        getResults.put("input_schema", resultsSchema);

        Map<String, Object> getLogs = new LinkedHashMap<>();
        getLogs.put("name", "get_scan_logs");
        getLogs.put("description", "Obtiene los logs de progreso de un escaneo.");
        Map<String, Object> logsSchema = new LinkedHashMap<>();
        logsSchema.put("type", "object");
        logsSchema.put("properties", scanIdProps);
        logsSchema.put("required", List.of("scanId"));
        getLogs.put("input_schema", logsSchema);

        Map<String, Object> pageProp = new LinkedHashMap<>();
        pageProp.put("type", "integer");
        pageProp.put("description", "Número de página (0-indexado). Por defecto: 0");
        Map<String, Object> historyProps = new LinkedHashMap<>();
        historyProps.put("page", pageProp);
        Map<String, Object> getHistory = new LinkedHashMap<>();
        getHistory.put("name", "get_history");
        getHistory.put("description", "Obtiene el historial de escaneos anteriores.");
        Map<String, Object> historySchema = new LinkedHashMap<>();
        historySchema.put("type", "object");
        historySchema.put("properties", historyProps);
        historySchema.put("required", List.of());
        getHistory.put("input_schema", historySchema);

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
