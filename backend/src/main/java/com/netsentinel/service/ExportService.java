package com.netsentinel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netsentinel.dto.AnalysisReport;
import com.netsentinel.dto.ScanResultsResponse;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Service
public class ExportService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.forLanguageTag("es"));

    private final TemplateEngine templateEngine;
    private final ObjectMapper objectMapper;

    public ExportService(TemplateEngine templateEngine, ObjectMapper objectMapper) {
        this.templateEngine = templateEngine;
        this.objectMapper = objectMapper;
    }

    public byte[] generatePdf(ScanResultsResponse scan, AnalysisReport analysis) throws IOException {
        Context ctx = new Context();
        ctx.setVariable("scan", scan);
        ctx.setVariable("analysis", analysis);
        ctx.setVariable("findings", analysis != null ? analysis.findings() : List.of());
        ctx.setVariable("recommendations", analysis != null ? analysis.recommendations() : List.of());
        ctx.setVariable("hosts", scan.hosts());
        ctx.setVariable("totalHosts", scan.hosts().size());
        ctx.setVariable("totalPorts", scan.hosts().stream().mapToLong(h -> h.ports().size()).sum());
        ctx.setVariable("totalCves", scan.hosts().stream().flatMap(h -> h.ports().stream()).mapToLong(p -> p.cves().size()).sum());
        ctx.setVariable("totalFindings", analysis != null ? analysis.findings().size() : 0);
        ctx.setVariable("riskLevel", analysis != null ? analysis.riskLevel() : "INFO");
        ctx.setVariable("riskScore", analysis != null ? String.format("%.1f", analysis.riskScore()) : "0.0");
        ctx.setVariable("riskTitle", riskTitle(analysis != null ? analysis.riskLevel() : "INFO"));
        ctx.setVariable("webFindings", scan.hosts().stream().flatMap(h -> h.webFindings().stream()).toList());
        ctx.setVariable("reportDate", scan.startedAt() != null
                ? FMT.format(scan.startedAt().atZone(ZoneId.systemDefault())) : "—");
        ctx.setVariable("duration", formatDuration(scan));

        String html = templateEngine.process("report", ctx);

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(baos);
            builder.run();
            return baos.toByteArray();
        }
    }

    public byte[] generateJson(ScanResultsResponse scan) throws Exception {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(scan);
    }

    public byte[] generateCsv(ScanResultsResponse scan, AnalysisReport analysis) {
        StringBuilder sb = new StringBuilder('﻿');
        sb.append("Severity,Title,Device,Port,Service,Related CVEs,Detail\n");
        if (analysis != null) {
            for (AnalysisReport.Finding f : analysis.findings()) {
                sb.append(escape(f.severity())).append(',');
                sb.append(escape(f.title())).append(',');
                sb.append(escape(f.host())).append(',');
                sb.append(f.port()).append(',');
                sb.append(escape(f.service())).append(',');
                sb.append(escape(String.join("; ", f.relatedCves()))).append(',');
                sb.append(escape(f.detail())).append('\n');
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String escape(String v) {
        if (v == null || v.isEmpty()) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private String riskTitle(String level) {
        return switch (level) {
            case "CRITICAL" -> "Critical risk — immediate action required";
            case "HIGH"     -> "High risk — urgent measures recommended";
            case "MEDIUM"   -> "Medium risk — review recommended";
            case "LOW"      -> "Low risk — acceptable state";
            default         -> "No significant vulnerabilities";
        };
    }

    private String formatDuration(ScanResultsResponse scan) {
        if (scan.completedAt() == null) return "—";
        long secs = scan.completedAt().getEpochSecond() - scan.startedAt().getEpochSecond();
        if (secs < 60) return secs + "s";
        return (secs / 60) + "m " + (secs % 60) + "s";
    }
}
