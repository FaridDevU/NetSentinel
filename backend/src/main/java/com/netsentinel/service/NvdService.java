package com.netsentinel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netsentinel.entity.CveEntry;
import com.netsentinel.entity.NetworkPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class NvdService {

    private static final Logger log = LoggerFactory.getLogger(NvdService.class);
    private static final int MAX_RESULTS = 20;

    @Value("${nvd.api.base-url}")
    private String baseUrl;

    @Value("${nvd.api.key:}")
    private String apiKey;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    public List<CveEntry> lookupCves(String service, String version, NetworkPort port) {
        if (service == null || service.isBlank() || version == null || version.isBlank()) {
            return List.of();
        }

        String query = service.trim() + " " + version.trim();
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = baseUrl + "?keywordSearch=" + encodedQuery + "&resultsPerPage=" + MAX_RESULTS;

        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "NetSentinel/1.0");

            if (apiKey != null && !apiKey.isBlank()) {
                requestBuilder.header("apiKey", apiKey);
            }

            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200) {
                log.warn("NVD API returned status {} for query: {}", response.statusCode(), query);
                return List.of();
            }

            return parseCveResponse(response.body(), port);

        } catch (Exception e) {
            log.warn("Failed to query NVD for {} {}: {}", service, version, e.getMessage());
            return List.of();
        }
    }

    private List<CveEntry> parseCveResponse(String body, NetworkPort port) {
        List<CveEntry> entries = new ArrayList<>();

        try {
            JsonNode root = mapper.readTree(body);
            JsonNode vulnerabilities = root.path("vulnerabilities");

            if (vulnerabilities.isMissingNode() || !vulnerabilities.isArray()) {
                return List.of();
            }

            for (JsonNode vuln : vulnerabilities) {
                JsonNode cve = vuln.path("cve");
                CveEntry entry = new CveEntry();
                entry.setPort(port);
                entry.setCveId(cve.path("id").asText());

                JsonNode descriptions = cve.path("descriptions");
                if (descriptions.isArray() && descriptions.size() > 0) {
                    entry.setDescription(descriptions.get(0).path("value").asText());
                }

                JsonNode metrics = cve.path("metrics");
                Double score = extractCvssScore(metrics);
                String vector = extractCvssVector(metrics);
                entry.setCvssScore(score);
                entry.setCvssVector(vector);
                entry.setPublishedDate(cve.path("published").asText(null));
                entry.setNvdUrl("https://nvd.nist.gov/vuln/detail/" + entry.getCveId());

                entries.add(entry);
            }

        } catch (Exception e) {
            log.error("Failed to parse NVD response", e);
        }

        return entries;
    }

    private Double extractCvssScore(JsonNode metrics) {
        for (String key : new String[]{"cvssMetricV31", "cvssMetricV30", "cvssMetricV2"}) {
            JsonNode metric = metrics.path(key);
            if (metric.isArray() && metric.size() > 0) {
                double score = metric.get(0).path("cvssData").path("baseScore").asDouble(-1);
                if (score >= 0) return score;
            }
        }
        return null;
    }

    private String extractCvssVector(JsonNode metrics) {
        for (String key : new String[]{"cvssMetricV31", "cvssMetricV30", "cvssMetricV2"}) {
            JsonNode metric = metrics.path(key);
            if (metric.isArray() && metric.size() > 0) {
                String vector = metric.get(0).path("cvssData").path("vectorString").asText(null);
                if (vector != null && !vector.isBlank()) return vector;
            }
        }
        return null;
    }
}
