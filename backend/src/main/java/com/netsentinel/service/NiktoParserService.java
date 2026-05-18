package com.netsentinel.service;

import com.netsentinel.entity.NetworkHost;
import com.netsentinel.entity.WebFinding;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class NiktoParserService {

    private static final String FINDING_PREFIX = "+ ";
    private static final String[] SKIP_PREFIXES = {
            "+ Target IP:", "+ Target Hostname:", "+ Target Port:",
            "+ Start Time:", "+ End Time:", "+ Server:", "+ Retrieved",
            "+ Allowed HTTP", "+ No CGI", "+ 1 host(s) tested"
    };

    public List<WebFinding> parse(String output, NetworkHost host, String url) {
        List<WebFinding> findings = new ArrayList<>();
        if (output == null || output.isBlank()) return findings;

        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith(FINDING_PREFIX)) continue;
            if (shouldSkip(trimmed)) continue;

            String text = trimmed.substring(FINDING_PREFIX.length()).trim();
            if (text.isBlank()) continue;

            String severity = classifyFinding(text);
            findings.add(new WebFinding(host, "nikto", url, null, text, severity));
        }

        return findings;
    }

    private boolean shouldSkip(String line) {
        for (String prefix : SKIP_PREFIXES) {
            if (line.startsWith(prefix)) return true;
        }
        return false;
    }

    private String classifyFinding(String text) {
        String lower = text.toLowerCase(Locale.ROOT);

        if (containsAny(lower, "injection", "xss", "cross-site", "rce", "remote code",
                "command exec", "arbitrary", "critical", "exploit")) {
            return "HIGH";
        }
        if (containsAny(lower, "osvdb", "cve-")) {
            return "MEDIUM";
        }
        if (containsAny(lower, "admin", "login", "backup", "config", "exposed",
                "sensitive", "interesting", "password", "credential", "bypass",
                "vulnerable", "phpinfo", "phpmyadmin", ".git", ".env", "debug")) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }
}
