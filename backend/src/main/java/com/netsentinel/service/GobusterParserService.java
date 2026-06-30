package com.netsentinel.service;

import com.netsentinel.entity.NetworkHost;
import com.netsentinel.entity.WebFinding;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GobusterParserService {

    private static final Pattern LINE = Pattern.compile("^(/\\S+)\\s+\\(Status: (\\d+)\\)");
    private static final Set<Integer> INTERESTING_STATUS = Set.of(200, 403);
    private static final Set<String> HIGH_PATHS = Set.of(
            "admin", "administrator", "login", "wp-admin", "phpmyadmin",
            ".git", ".env", "backup", "backups", "config", "configuration",
            "secret", "secrets", "credentials", "password", "passwords",
            "db", "database", "api", "swagger", "actuator", "console",
            "shell", "cmd", "exec", "upload", "uploads", "private"
    );

    public List<WebFinding> parse(String output, NetworkHost host, String url) {
        List<WebFinding> findings = new ArrayList<>();
        if (output == null || output.isBlank()) return findings;

        for (String line : output.split("\n")) {
            Matcher m = LINE.matcher(line.trim());
            if (!m.find()) continue;

            String path = m.group(1);
            int status;
            try {
                status = Integer.parseInt(m.group(2));
            } catch (NumberFormatException e) {
                continue;
            }

            if (!INTERESTING_STATUS.contains(status)) continue;

            String severity = classifyPath(path, status);
            String description = buildDescription(path, status);
            findings.add(new WebFinding(host, "gobuster", url + path, status, description, severity));
        }

        return findings;
    }

    private String classifyPath(String path, int status) {
        String lower = path.toLowerCase();
        boolean sensitive = HIGH_PATHS.stream().anyMatch(lower::contains);
        if (sensitive && status == 200) return "HIGH";
        if (sensitive) return "MEDIUM";
        return "LOW";
    }

    private String buildDescription(String path, int status) {
        String statusLabel = status == 200 ? "accessible (200 OK)" : "found but restricted (403)";
        return "Web path " + statusLabel + ": " + path;
    }
}
