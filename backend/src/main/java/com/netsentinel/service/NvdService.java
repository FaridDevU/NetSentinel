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
import java.util.concurrent.ConcurrentHashMap;

@Service
public class NvdService {

    private static final Logger log = LoggerFactory.getLogger(NvdService.class);
    private static final int MAX_RESULTS = 20;
    private static final long REQUEST_INTERVAL_MS = 650;

    @Value("${nvd.api.base-url}")
    private String baseUrl;

    @Value("${nvd.api.key:}")
    private String apiKey;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    private final ConcurrentHashMap<String, List<CveCacheData>> cache = new ConcurrentHashMap<>();
    private final Object rateLimitLock = new Object();
    private long lastRequestTime = 0;

    private record CveCacheData(
            String cveId,
            String description,
            Double cvssScore,
            String cvssVector,
            String publishedDate,
            String nvdUrl
    ) {}

    public List<CveEntry> lookupCves(String service, String version, NetworkPort port) {
        if (service == null || service.isBlank()) {
            return List.of();
        }

        String query = version != null && !version.isBlank()
                ? service.trim() + " " + version.trim()
                : service.trim();

        String cacheKey = query.toLowerCase();
        List<CveCacheData> cached = cache.get(cacheKey);
        if (cached != null) {
            log.debug("NVD cache hit for: {}", cacheKey);
            return cached.stream().map(d -> toCveEntry(d, port)).toList();
        }

        applyRateLimit();

        List<CveCacheData> result = fetchFromNvd(query);
        cache.put(cacheKey, result);
        return result.stream().map(d -> toCveEntry(d, port)).toList();
    }

    private void applyRateLimit() {
        synchronized (rateLimitLock) {
            long now = System.currentTimeMillis();
            long waitMs = REQUEST_INTERVAL_MS - (now - lastRequestTime);
            if (waitMs > 0) {
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastRequestTime = System.currentTimeMillis();
        }
    }

    private List<CveCacheData> fetchFromNvd(String query) {
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

            if (response.statusCode() == 429) {
                log.warn("NVD API rate limit hit (429). Consider setting nvd.api.key in config.");
                return List.of();
            }

            if (response.statusCode() != 200) {
                log.warn("NVD API returned status {} for query: {}", response.statusCode(), query);
                return List.of();
            }

            return parseCveResponse(response.body());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            log.warn("Failed to query NVD for '{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    private List<CveCacheData> parseCveResponse(String body) {
        List<CveCacheData> entries = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode vulnerabilities = root.path("vulnerabilities");

            if (vulnerabilities.isMissingNode() || !vulnerabilities.isArray()) {
                return List.of();
            }

            for (JsonNode vuln : vulnerabilities) {
                JsonNode cve = vuln.path("cve");
                String cveId = cve.path("id").asText(null);
                if (cveId == null || cveId.isBlank()) continue;

                String description = null;
                JsonNode descriptions = cve.path("descriptions");
                if (descriptions.isArray() && !descriptions.isEmpty()) {
                    description = descriptions.get(0).path("value").asText(null);
                }

                JsonNode metrics = cve.path("metrics");
                entries.add(new CveCacheData(
                        cveId,
                        description,
                        extractCvssScore(metrics),
                        extractCvssVector(metrics),
                        cve.path("published").asText(null),
                        "https://nvd.nist.gov/vuln/detail/" + cveId
                ));
            }
        } catch (Exception e) {
            log.error("Failed to parse NVD response", e);
        }
        return entries;
    }

    private CveEntry toCveEntry(CveCacheData data, NetworkPort port) {
        CveEntry entry = new CveEntry();
        entry.setPort(port);
        entry.setCveId(data.cveId());
        entry.setDescription(data.description());
        entry.setCvssScore(data.cvssScore());
        entry.setCvssVector(data.cvssVector());
        entry.setPublishedDate(data.publishedDate());
        entry.setNvdUrl(data.nvdUrl());
        return entry;
    }

    private Double extractCvssScore(JsonNode metrics) {
        for (String key : new String[]{"cvssMetricV31", "cvssMetricV30", "cvssMetricV2"}) {
            JsonNode metric = metrics.path(key);
            if (metric.isArray() && !metric.isEmpty()) {
                double score = metric.get(0).path("cvssData").path("baseScore").asDouble(-1);
                if (score >= 0) return score;
            }
        }
        return null;
    }

    private String extractCvssVector(JsonNode metrics) {
        for (String key : new String[]{"cvssMetricV31", "cvssMetricV30", "cvssMetricV2"}) {
            JsonNode metric = metrics.path(key);
            if (metric.isArray() && !metric.isEmpty()) {
                String vector = metric.get(0).path("cvssData").path("vectorString").asText(null);
                if (vector != null && !vector.isBlank()) return vector;
            }
        }
        return null;
    }
}
