package com.netsentinel.service;

import com.netsentinel.config.RiskCatalogProperties;
import com.netsentinel.dto.AnalysisReport;
import com.netsentinel.dto.AnalysisReport.Finding;
import com.netsentinel.dto.AnalysisReport.HostSummary;
import com.netsentinel.entity.CveEntry;
import com.netsentinel.entity.NetworkHost;
import com.netsentinel.entity.NetworkPort;
import com.netsentinel.entity.WebFinding;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class AnalysisService {

    private final Map<Integer, RiskCatalogProperties.ServiceRisk> knownRisks;
    private final Set<Integer> databasePorts;
    private final MessageSource messages;

    public AnalysisService(RiskCatalogProperties riskCatalog, MessageSource messages) {
        this.knownRisks = riskCatalog.asMap();
        this.databasePorts = riskCatalog.getDatabasePorts();
        this.messages = messages;
    }

    public AnalysisReport analyze(String target, List<NetworkHost> hosts) {
        return analyze(target, hosts, Locale.ENGLISH);
    }

    public AnalysisReport analyze(String target, List<NetworkHost> hosts, Locale locale) {
        List<Finding> findings = new ArrayList<>();
        List<HostSummary> hostSummaries = new ArrayList<>();

        for (NetworkHost host : hosts) {
            analyzeHost(host, findings, hostSummaries, locale);
        }

        findings.sort(Comparator.comparingInt(f -> -severityScore(f.severity())));

        double riskScore = calculateRiskScore(findings);
        String riskLevel = scoreToLevel(riskScore);
        String summary = buildSummary(target, hosts, findings, riskLevel, locale);
        List<String> recommendations = buildRecommendations(findings, hosts, locale);

        return new AnalysisReport(riskLevel, riskScore, summary, findings, hostSummaries, recommendations);
    }

    private void analyzeHost(NetworkHost host, List<Finding> findings, List<HostSummary> summaries, Locale locale) {
        List<NetworkPort> openPorts = host.getPorts().stream()
                .filter(p -> "open".equals(p.getState()))
                .toList();

        int totalCves = 0;

        for (NetworkPort port : openPorts) {
            List<CveEntry> significantCves = port.getCves().stream()
                    .filter(c -> c.getCvssScore() != null && c.getCvssScore() >= 4.0)
                    .sorted(Comparator.comparingDouble(c -> -c.getCvssScore()))
                    .toList();

            if (!significantCves.isEmpty()) {
                Map<String, List<CveEntry>> bySeverity = significantCves.stream()
                        .collect(Collectors.groupingBy(c -> cvssToSeverity(c.getCvssScore())));

                for (Map.Entry<String, List<CveEntry>> entry : bySeverity.entrySet()) {
                    String sev = entry.getKey();
                    List<CveEntry> group = entry.getValue();
                    findings.add(buildCveFinding(host.getIp(), port, sev, group, locale));
                }
                totalCves += significantCves.size();

            } else if (knownRisks.containsKey(port.getPortNumber())) {
                RiskCatalogProperties.ServiceRisk risk = knownRisks.get(port.getPortNumber());
                findings.add(new Finding(
                        risk.getLevel(),
                        buildServiceTitle(port, locale),
                        msg("risk." + port.getPortNumber() + ".reason", locale),
                        host.getIp(),
                        port.getPortNumber(),
                        port.getService() != null ? port.getService() : "unknown",
                        List.of()
                ));

            } else if (databasePorts.contains(port.getPortNumber())) {
                findings.add(new Finding(
                        "MEDIUM",
                        msg("finding.db.title", locale, String.valueOf(port.getPortNumber())),
                        buildDbExposureDetail(host.getIp(), port, locale),
                        host.getIp(),
                        port.getPortNumber(),
                        port.getService() != null ? port.getService() : "database",
                        List.of()
                ));
            }
        }

        analyzeWebFindings(host, findings, locale);
        addLowExposureFinding(host, openPorts, findings, locale);

        String hostRisk = calculateHostRisk(openPorts, findings, host.getIp());
        summaries.add(new HostSummary(
                host.getIp(),
                hostRisk,
                openPorts.size(),
                totalCves,
                buildHostSummary(host, openPorts, totalCves, locale)
        ));
    }

    private void analyzeWebFindings(NetworkHost host, List<Finding> findings, Locale locale) {
        List<WebFinding> highFindings = host.getWebFindings().stream()
                .filter(wf -> "HIGH".equals(wf.getSeverity()))
                .toList();
        List<WebFinding> mediumFindings = host.getWebFindings().stream()
                .filter(wf -> "MEDIUM".equals(wf.getSeverity()))
                .toList();

        if (!highFindings.isEmpty()) {
            String paths = highFindings.stream()
                    .map(WebFinding::getUrl)
                    .distinct().limit(3)
                    .collect(Collectors.joining(", "));
            findings.add(new Finding(
                    "HIGH",
                    msg("finding.web.high.title", locale, host.getIp()),
                    msg("finding.web.high.detail", locale, highFindings.size(), paths),
                    host.getIp(), 80, "http", List.of()
            ));
        } else if (!mediumFindings.isEmpty()) {
            findings.add(new Finding(
                    "MEDIUM",
                    msg("finding.web.medium.title", locale, host.getIp()),
                    msg("finding.web.medium.detail", locale, mediumFindings.size()),
                    host.getIp(), 80, "http", List.of()
            ));
        }
    }

    private void addLowExposureFinding(NetworkHost host, List<NetworkPort> openPorts, List<Finding> findings, Locale locale) {
        if (openPorts.isEmpty()) return;
        boolean hostAlreadyHasFinding = findings.stream().anyMatch(f -> f.host().equals(host.getIp()));
        if (hostAlreadyHasFinding) return;

        String ports = openPorts.stream()
                .map(p -> p.getPortNumber() + "/" + (p.getService() != null ? p.getService() : p.getProtocol()))
                .limit(5)
                .collect(Collectors.joining(", "));

        NetworkPort first = openPorts.get(0);
        findings.add(new Finding(
                "LOW",
                msg("finding.lowexposure.title", locale, host.getIp()),
                msg("finding.lowexposure.detail", locale, ports),
                host.getIp(),
                first.getPortNumber(),
                first.getService() != null ? first.getService() : "unknown",
                List.of()
        ));
    }

    private Finding buildCveFinding(String ip, NetworkPort port, String severity, List<CveEntry> cves, Locale locale) {
        String service = port.getService() != null ? port.getService() : "service";
        String version = port.getVersion() != null ? " " + port.getVersion() : "";
        int count = cves.size();
        CveEntry top = cves.get(0);

        String severityWord = msg("severity." + severity.toLowerCase(), locale);
        String title = msg("finding.cve.title", locale,
                capitalize(service), version, count, severityWord, String.valueOf(port.getPortNumber()));

        String desc = top.getDescription() != null ? top.getDescription() : msg("cve.nodescription", locale);
        String excerpt = desc.length() > 200 ? desc.substring(0, 200) + "..." : desc;

        String detail = msg("finding.cve.detail", locale,
                ip, service, version, String.valueOf(port.getPortNumber()), port.getProtocol(),
                count, severityWord,
                cves.stream().map(CveEntry::getCveId).collect(Collectors.joining(", ")),
                top.getCveId(), top.getCvssScore(), excerpt);

        List<String> cveIds = cves.stream().map(CveEntry::getCveId).toList();
        return new Finding(severity, title, detail, ip, port.getPortNumber(),
                port.getService() != null ? port.getService() : "unknown", cveIds);
    }

    private String buildServiceTitle(NetworkPort port, Locale locale) {
        String name = port.getService() != null ? capitalize(port.getService()) : msg("service.default", locale);
        String label = msg("risk." + port.getPortNumber() + ".label", locale);
        return msg("finding.service.title", locale, name, String.valueOf(port.getPortNumber()), label);
    }

    private String buildDbExposureDetail(String ip, NetworkPort port, Locale locale) {
        String service = port.getService() != null ? port.getService() : msg("service.database.default", locale);
        return msg("finding.db.detail", locale, String.valueOf(port.getPortNumber()), ip, service);
    }

    private String buildHostSummary(NetworkHost host, List<NetworkPort> openPorts, int totalCves, Locale locale) {
        if (openPorts.isEmpty()) return msg("host.summary.noports", locale);

        String services = openPorts.stream()
                .map(p -> p.getService() != null ? p.getService() : String.valueOf(p.getPortNumber()))
                .distinct().limit(4)
                .collect(Collectors.joining(", "));

        String cveInfo = totalCves > 0 ? msg("host.summary.cveinfo", locale, totalCves) : "";
        String osInfo = host.getOs() != null ? msg("host.summary.os", locale, host.getOs()) : "";

        return msg("host.summary.main", locale, openPorts.size(), services, osInfo, cveInfo);
    }

    private String calculateHostRisk(List<NetworkPort> openPorts, List<Finding> findings, String ip) {
        int maxSeverity = findings.stream()
                .filter(f -> f.host().equals(ip))
                .mapToInt(f -> severityScore(f.severity()))
                .max()
                .orElse(0);
        return scoreToLevel(maxSeverity);
    }

    private double calculateRiskScore(List<Finding> findings) {
        if (findings.isEmpty()) return 0.0;
        double max = findings.stream()
                .mapToInt(f -> severityScore(f.severity()))
                .max()
                .orElse(0);
        double avg = findings.stream()
                .mapToInt(f -> severityScore(f.severity()))
                .average()
                .orElse(0);
        double score = (max * 0.7) + (avg * 0.3);
        return Math.round(score * 10.0) / 10.0;
    }

    private String buildSummary(String target, List<NetworkHost> hosts, List<Finding> findings, String riskLevel, Locale locale) {
        int totalOpen = hosts.stream()
                .mapToInt(h -> (int) h.getPorts().stream().filter(p -> "open".equals(p.getState())).count())
                .sum();
        long critical = findings.stream().filter(f -> "CRITICAL".equals(f.severity())).count();
        long high = findings.stream().filter(f -> "HIGH".equals(f.severity())).count();
        long medium = findings.stream().filter(f -> "MEDIUM".equals(f.severity())).count();

        StringBuilder sb = new StringBuilder();
        sb.append(msg("summary.intro", locale, target, hosts.size(), totalOpen));
        sb.append(' ');

        if (critical > 0 || high > 0) {
            sb.append(msg("summary.problems", locale, findings.size(), buildSeveritySummary(critical, high, medium, locale)));
        } else if (medium > 0) {
            sb.append(msg("summary.mediumonly", locale, medium));
        } else {
            sb.append(msg("summary.none", locale));
        }

        sb.append(' ').append(riskSentence(riskLevel, locale));
        return sb.toString();
    }

    private String buildSeveritySummary(long critical, long high, long medium, Locale locale) {
        List<String> parts = new ArrayList<>();
        if (critical > 0) parts.add(msg("sevcount.critical", locale, critical));
        if (high > 0) parts.add(msg("sevcount.high", locale, high));
        if (medium > 0) parts.add(msg("sevcount.medium", locale, medium));
        return String.join(", ", parts) + ".";
    }

    private String riskSentence(String level, Locale locale) {
        String key = switch (level) {
            case "CRITICAL" -> "risk.sentence.critical";
            case "HIGH"     -> "risk.sentence.high";
            case "MEDIUM"   -> "risk.sentence.medium";
            case "LOW"      -> "risk.sentence.low";
            default         -> "risk.sentence.info";
        };
        return msg(key, locale);
    }

    private List<String> buildRecommendations(List<Finding> findings, List<NetworkHost> hosts, Locale locale) {
        Set<String> seen = new LinkedHashSet<>();

        for (Finding f : findings) {
            int port = f.port();

            if (!f.relatedCves().isEmpty()) {
                seen.add(msg("rec.cve", locale, capitalize(f.service()), f.relatedCves().size(), String.valueOf(port)));
            }

            if (port == 23) seen.add(msg("rec.telnet", locale));
            if (port == 21) seen.add(msg("rec.ftp", locale));
            if (port == 3389) seen.add(msg("rec.rdp", locale));
            if (port == 445) seen.add(msg("rec.smb", locale));
            if (port == 6379) seen.add(msg("rec.redis", locale));
            if (port == 27017) seen.add(msg("rec.mongodb", locale));
            if (port == 2375) seen.add(msg("rec.docker", locale));
            if (databasePorts.contains(port) && f.relatedCves().isEmpty()) {
                seen.add(msg("rec.database", locale, String.valueOf(port), capitalize(f.service())));
            }
        }

        long hasVulnerabilities = findings.stream().filter(f -> !f.relatedCves().isEmpty()).count();
        if (hasVulnerabilities > 0) {
            seen.add(msg("rec.updateschedule", locale));
        }
        if (findings.size() > 2) {
            seen.add(msg("rec.segmentation", locale));
        }

        long highWebCount = hosts.stream()
                .flatMap(h -> h.getWebFindings().stream())
                .filter(wf -> "HIGH".equals(wf.getSeverity()))
                .count();
        if (highWebCount > 0) {
            seen.add(msg("rec.webhigh", locale));
        }

        return new ArrayList<>(seen);
    }

    private int severityScore(String severity) {
        return switch (severity) {
            case "CRITICAL" -> 10;
            case "HIGH"     -> 7;
            case "MEDIUM"   -> 4;
            case "LOW"      -> 1;
            default         -> 0;
        };
    }

    private String scoreToLevel(double score) {
        if (score >= 9.0) return "CRITICAL";
        if (score >= 7.0) return "HIGH";
        if (score >= 4.0) return "MEDIUM";
        if (score >= 1.0) return "LOW";
        return "INFO";
    }

    private String cvssToSeverity(Double score) {
        if (score == null) return "LOW";
        if (score >= 9.0) return "CRITICAL";
        if (score >= 7.0) return "HIGH";
        if (score >= 4.0) return "MEDIUM";
        return "LOW";
    }

    private String msg(String code, Locale locale, Object... args) {
        return messages.getMessage(code, args, locale);
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
