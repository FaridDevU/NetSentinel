package com.netsentinel.service;

import com.netsentinel.dto.AnalysisReport;
import com.netsentinel.dto.AnalysisReport.Finding;
import com.netsentinel.dto.AnalysisReport.HostSummary;
import com.netsentinel.entity.CveEntry;
import com.netsentinel.entity.NetworkHost;
import com.netsentinel.entity.NetworkPort;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class AnalysisService {

    private record ServiceRisk(String level, String reason) {}

    private static final Map<Integer, ServiceRisk> KNOWN_RISKS = Map.ofEntries(
            Map.entry(21,    new ServiceRisk("MEDIUM", "FTP transmits credentials and data in cleartext. A passive network capture is sufficient to steal login credentials.")),
            Map.entry(23,    new ServiceRisk("HIGH",   "Telnet is an unencrypted protocol with no integrity protection. All session data, including passwords, are transmitted in plaintext.")),
            Map.entry(445,   new ServiceRisk("HIGH",   "SMB is a frequent vector for lateral movement and ransomware propagation. Exposure to untrusted networks significantly raises risk.")),
            Map.entry(3389,  new ServiceRisk("HIGH",   "RDP is one of the most targeted services for brute-force attacks and exploitation of authentication bypasses.")),
            Map.entry(5900,  new ServiceRisk("MEDIUM", "VNC can be configured without authentication or with weak credentials, allowing full graphical remote access.")),
            Map.entry(6379,  new ServiceRisk("HIGH",   "Redis runs without authentication by default. An attacker with network access can read, write, or delete all cached data and execute server-side scripts.")),
            Map.entry(27017, new ServiceRisk("HIGH",   "MongoDB is frequently deployed without authentication. A publicly reachable instance exposes all databases to read and write access.")),
            Map.entry(11211, new ServiceRisk("MEDIUM", "Memcached has no authentication mechanism. Exposed instances can leak cached application data and be abused for amplification attacks.")),
            Map.entry(2375,  new ServiceRisk("CRITICAL","Docker daemon TCP API exposed without TLS. Any client can spawn containers, mount host filesystems, and achieve full host compromise.")),
            Map.entry(9200,  new ServiceRisk("MEDIUM", "Elasticsearch REST API exposed without authentication. All indexed data is readable and the cluster can be modified by anyone with network access."))
    );

    private static final Set<Integer> DATABASE_PORTS = Set.of(1433, 1521, 3306, 5432, 5984);

    public AnalysisReport analyze(String target, List<NetworkHost> hosts) {
        List<Finding> findings = new ArrayList<>();
        List<HostSummary> hostSummaries = new ArrayList<>();

        for (NetworkHost host : hosts) {
            analyzeHost(host, findings, hostSummaries);
        }

        findings.sort(Comparator.comparingInt(f -> -severityScore(f.severity())));

        double riskScore = calculateRiskScore(findings);
        String riskLevel = scoreToLevel(riskScore);
        String summary = buildSummary(target, hosts, findings, riskLevel);
        List<String> recommendations = buildRecommendations(findings, hosts);

        return new AnalysisReport(riskLevel, riskScore, summary, findings, hostSummaries, recommendations);
    }

    private void analyzeHost(NetworkHost host, List<Finding> findings, List<HostSummary> summaries) {
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
                    findings.add(buildCveFinding(host.getIp(), port, sev, group));
                }
                totalCves += significantCves.size();

            } else if (KNOWN_RISKS.containsKey(port.getPortNumber())) {
                ServiceRisk risk = KNOWN_RISKS.get(port.getPortNumber());
                findings.add(new Finding(
                        risk.level(),
                        buildServiceTitle(port),
                        risk.reason(),
                        host.getIp(),
                        port.getPortNumber(),
                        port.getService() != null ? port.getService() : "unknown",
                        List.of()
                ));

            } else if (DATABASE_PORTS.contains(port.getPortNumber())) {
                findings.add(new Finding(
                        "MEDIUM",
                        "Database service directly reachable on port " + port.getPortNumber(),
                        buildDbExposureDetail(host.getIp(), port),
                        host.getIp(),
                        port.getPortNumber(),
                        port.getService() != null ? port.getService() : "database",
                        List.of()
                ));
            }
        }

        String hostRisk = calculateHostRisk(openPorts, findings, host.getIp());
        summaries.add(new HostSummary(
                host.getIp(),
                hostRisk,
                openPorts.size(),
                totalCves,
                buildHostSummary(host, openPorts, totalCves)
        ));
    }

    private Finding buildCveFinding(String ip, NetworkPort port, String severity, List<CveEntry> cves) {
        String service = port.getService() != null ? port.getService() : "service";
        String version = port.getVersion() != null ? " " + port.getVersion() : "";
        int count = cves.size();
        CveEntry top = cves.get(0);

        String title = String.format("%s%s — %d %s CVE%s on port %d",
                capitalize(service), version, count, severity.toLowerCase(), count > 1 ? "s" : "", port.getPortNumber());

        String desc = top.getDescription() != null ? top.getDescription() : "No description available.";
        String excerpt = desc.length() > 200 ? desc.substring(0, 200) + "..." : desc;

        String detail = String.format(
                "Host %s exposes %s%s on port %d/%s. %d %s-severity vulnerabilit%s identified: %s. " +
                "Top finding (%s, CVSS %.1f): %s",
                ip, service, version, port.getPortNumber(), port.getProtocol(),
                count, severity.toLowerCase(), count > 1 ? "ies" : "y",
                cves.stream().map(CveEntry::getCveId).collect(Collectors.joining(", ")),
                top.getCveId(), top.getCvssScore(), excerpt
        );

        List<String> cveIds = cves.stream().map(CveEntry::getCveId).toList();
        return new Finding(severity, title, detail, ip, port.getPortNumber(),
                port.getService() != null ? port.getService() : "unknown", cveIds);
    }

    private String buildServiceTitle(NetworkPort port) {
        String name = port.getService() != null ? capitalize(port.getService()) : "Service";
        return name + " exposed on port " + port.getPortNumber() + " — " + getServiceRiskSuffix(port.getPortNumber());
    }

    private String getServiceRiskSuffix(int port) {
        return switch (port) {
            case 21 -> "cleartext protocol";
            case 23 -> "unencrypted remote access";
            case 445 -> "lateral movement vector";
            case 3389 -> "remote desktop brute-force target";
            case 5900 -> "unauthenticated graphical access";
            case 6379 -> "unauthenticated data store";
            case 27017 -> "unauthenticated database";
            case 11211 -> "unauthenticated cache";
            case 2375 -> "unauthenticated container API";
            case 9200 -> "unauthenticated search index";
            default -> "known risk";
        };
    }

    private String buildDbExposureDetail(String ip, NetworkPort port) {
        String service = port.getService() != null ? port.getService() : "database";
        return String.format(
                "Port %d on %s exposes a %s instance directly to the network. " +
                "Database services should not be directly reachable from untrusted networks. " +
                "Network segmentation and firewall rules should restrict access to authorized clients only.",
                port.getPortNumber(), ip, service);
    }

    private String buildHostSummary(NetworkHost host, List<NetworkPort> openPorts, int totalCves) {
        if (openPorts.isEmpty()) return "No open ports detected on this host.";

        String services = openPorts.stream()
                .map(p -> p.getService() != null ? p.getService() : String.valueOf(p.getPortNumber()))
                .distinct().limit(4)
                .collect(Collectors.joining(", "));

        String cveInfo = totalCves > 0
                ? String.format(" %d exploitable vulnerabilit%s detected.", totalCves, totalCves > 1 ? "ies" : "y")
                : "";

        String osInfo = host.getOs() != null ? " Running " + host.getOs() + "." : "";

        return String.format("%d open port%s exposing: %s.%s%s",
                openPorts.size(), openPorts.size() > 1 ? "s" : "", services, osInfo, cveInfo);
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

    private String buildSummary(String target, List<NetworkHost> hosts, List<Finding> findings, String riskLevel) {
        int totalOpen = hosts.stream()
                .mapToInt(h -> (int) h.getPorts().stream().filter(p -> "open".equals(p.getState())).count())
                .sum();
        long critical = findings.stream().filter(f -> "CRITICAL".equals(f.severity())).count();
        long high = findings.stream().filter(f -> "HIGH".equals(f.severity())).count();
        long medium = findings.stream().filter(f -> "MEDIUM".equals(f.severity())).count();

        String hostWord = hosts.size() == 1 ? "host" : "hosts";
        String portWord = totalOpen == 1 ? "open port" : "open ports";

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Scan of %s discovered %d %s with %d %s. ",
                target, hosts.size(), hostWord, totalOpen, portWord));

        if (critical > 0 || high > 0) {
            sb.append(String.format("%s found: %s",
                    findings.size() == 1 ? "1 security issue" : findings.size() + " security issues",
                    buildSeveritySummary(critical, high, medium)));
        } else if (medium > 0) {
            sb.append(String.format("%d medium-severity issue%s identified requiring attention.",
                    medium, medium > 1 ? "s" : ""));
        } else {
            sb.append("No significant vulnerabilities detected.");
        }

        sb.append(" ").append(riskSentence(riskLevel));
        return sb.toString();
    }

    private String buildSeveritySummary(long critical, long high, long medium) {
        List<String> parts = new ArrayList<>();
        if (critical > 0) parts.add(critical + " critical");
        if (high > 0) parts.add(high + " high");
        if (medium > 0) parts.add(medium + " medium");
        return String.join(", ", parts) + ".";
    }

    private String riskSentence(String level) {
        return switch (level) {
            case "CRITICAL" -> "Immediate remediation is required — active exploitation risk is high.";
            case "HIGH"     -> "Urgent corrective action is recommended before next exposure window.";
            case "MEDIUM"   -> "Scheduled patching and access restriction are advised.";
            case "LOW"      -> "Exposure is limited. Maintain regular monitoring and update cycles.";
            default         -> "No immediate action required.";
        };
    }

    private List<String> buildRecommendations(List<Finding> findings, List<NetworkHost> hosts) {
        Set<String> seen = new LinkedHashSet<>();

        for (Finding f : findings) {
            String svc = f.service().toLowerCase();
            int port = f.port();

            if (!f.relatedCves().isEmpty()) {
                String rec = "Update " + capitalize(f.service()) + " to the latest stable version to address " +
                        f.relatedCves().size() + " known CVE" + (f.relatedCves().size() > 1 ? "s" : "") + " on port " + port + ".";
                seen.add(rec);
            }

            if (port == 23) seen.add("Replace Telnet (port 23) with SSH for all remote access.");
            if (port == 21) seen.add("Replace FTP (port 21) with SFTP or FTPS to protect credentials in transit.");
            if (port == 3389) seen.add("Restrict RDP (port 3389) access via network-level firewall rules and enable Network Level Authentication.");
            if (port == 445) seen.add("Block SMB (port 445) at the perimeter. Enable SMB signing to prevent relay attacks.");
            if (port == 6379) seen.add("Enable Redis authentication (requirepass) and bind Redis to localhost or a private interface.");
            if (port == 27017) seen.add("Enable MongoDB authentication and restrict access to the database port by IP.");
            if (port == 2375) seen.add("Immediately disable the Docker TCP API or enforce TLS client authentication.");
            if (DATABASE_PORTS.contains(port) && f.relatedCves().isEmpty()) {
                seen.add("Restrict database port " + port + " (" + capitalize(f.service()) + ") to authorized application servers via firewall rules.");
            }
        }

        long hasVulnerabilities = findings.stream().filter(f -> !f.relatedCves().isEmpty()).count();
        if (hasVulnerabilities > 0) {
            seen.add("Establish a regular patching schedule — prioritize services with known CVEs.");
        }
        if (findings.size() > 2) {
            seen.add("Apply network segmentation to limit lateral movement between exposed services.");
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

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
