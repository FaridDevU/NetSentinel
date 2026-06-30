package com.netsentinel.service;

import com.netsentinel.config.RiskCatalogProperties;
import com.netsentinel.dto.AnalysisReport;
import com.netsentinel.dto.AnalysisReport.Finding;
import com.netsentinel.entity.CveEntry;
import com.netsentinel.entity.NetworkHost;
import com.netsentinel.entity.NetworkPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisServiceTest {

    private AnalysisService service;

    @BeforeEach
    void setUp() {
        service = new AnalysisService(buildRiskCatalog(), messages());
    }

    private MessageSource messages() {
        ResourceBundleMessageSource ms = new ResourceBundleMessageSource();
        ms.setBasename("messages");
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);
        return ms;
    }

    private RiskCatalogProperties buildRiskCatalog() {
        RiskCatalogProperties props = new RiskCatalogProperties();
        props.setDatabasePorts(Set.of(1433, 1521, 3306, 5432, 5984));
        props.setServices(List.of(
                risk(21, "MEDIUM"),
                risk(23, "HIGH"),
                risk(445, "HIGH"),
                risk(3389, "HIGH"),
                risk(2375, "CRITICAL")
        ));
        return props;
    }

    private RiskCatalogProperties.ServiceRisk risk(int port, String level) {
        RiskCatalogProperties.ServiceRisk r = new RiskCatalogProperties.ServiceRisk();
        r.setPort(port);
        r.setLevel(level);
        return r;
    }

    private NetworkHost host(String ip, NetworkPort... ports) {
        NetworkHost h = new NetworkHost();
        h.setIp(ip);
        List<NetworkPort> list = new ArrayList<>();
        for (NetworkPort p : ports) {
            p.setHost(h);
            list.add(p);
        }
        h.setPorts(list);
        return h;
    }

    private NetworkPort port(int number, String state, String service) {
        NetworkPort p = new NetworkPort();
        p.setPortNumber(number);
        p.setProtocol("tcp");
        p.setState(state);
        p.setService(service);
        p.setCves(new ArrayList<>());
        return p;
    }

    private NetworkPort portWithCve(int number, String service, String cveId, double cvssScore) {
        NetworkPort p = port(number, "open", service);
        CveEntry cve = new CveEntry();
        cve.setCveId(cveId);
        cve.setCvssScore(cvssScore);
        cve.setDescription("Test description for " + cveId);
        cve.setPort(p);
        p.getCves().add(cve);
        return p;
    }

    @Test
    void emptyNetwork_returnsInfoRisk() {
        AnalysisReport report = service.analyze("192.168.1.0/24", List.of());

        assertThat(report.riskLevel()).isEqualTo("INFO");
        assertThat(report.findings()).isEmpty();
        assertThat(report.recommendations()).isEmpty();
        assertThat(report.riskScore()).isEqualTo(0.0);
    }

    @Test
    void closedPortsOnly_noFindings() {
        NetworkHost h = host("192.168.1.1", port(80, "closed", "http"));

        AnalysisReport report = service.analyze("192.168.1.1", List.of(h));

        assertThat(report.findings()).isEmpty();
        assertThat(report.riskLevel()).isEqualTo("INFO");
    }

    @Test
    void dockerTcpExposed_criticalRisk() {
        NetworkHost h = host("192.168.1.10", port(2375, "open", "docker"));

        AnalysisReport report = service.analyze("192.168.1.0/24", List.of(h));

        assertThat(report.riskLevel()).isEqualTo("CRITICAL");
    }

    @Test
    void ftpOpen_mediumFinding() {
        NetworkHost h = host("192.168.1.1", port(21, "open", "ftp"));

        AnalysisReport report = service.analyze("192.168.1.1", List.of(h));

        assertThat(report.findings()).hasSize(1);
        Finding f = report.findings().get(0);
        assertThat(f.severity()).isEqualTo("MEDIUM");
        assertThat(f.title()).contains("21");
        assertThat(f.detail()).contains("clear text");
        assertThat(f.host()).isEqualTo("192.168.1.1");
        assertThat(f.port()).isEqualTo(21);
    }

    @Test
    void rdpOpen_highFinding() {
        NetworkHost h = host("192.168.1.5", port(3389, "open", "rdp"));

        AnalysisReport report = service.analyze("192.168.1.5", List.of(h));

        assertThat(report.findings()).hasSize(1);
        assertThat(report.findings().get(0).severity()).isEqualTo("HIGH");
        assertThat(report.findings().get(0).title()).contains("3389");
    }

    @Test
    void telnetOpen_highFinding() {
        NetworkHost h = host("10.0.0.1", port(23, "open", "telnet"));

        AnalysisReport report = service.analyze("10.0.0.1", List.of(h));

        Finding f = report.findings().get(0);
        assertThat(f.severity()).isEqualTo("HIGH");
        assertThat(f.detail()).contains("plaintext");
    }

    @Test
    void dockerTcpOpen_criticalFinding() {
        NetworkHost h = host("192.168.1.10", port(2375, "open", "docker"));

        AnalysisReport report = service.analyze("192.168.1.10", List.of(h));

        Finding f = report.findings().get(0);
        assertThat(f.severity()).isEqualTo("CRITICAL");
        assertThat(f.title()).contains("2375");
        assertThat(f.detail()).contains("TLS");
    }

    @Test
    void exposedDatabase_mediumFinding() {
        NetworkHost h = host("192.168.1.20", port(3306, "open", "mysql"));

        AnalysisReport report = service.analyze("192.168.1.20", List.of(h));

        assertThat(report.findings()).hasSize(1);
        Finding f = report.findings().get(0);
        assertThat(f.severity()).isEqualTo("MEDIUM");
        assertThat(f.title()).contains("3306");
        assertThat(f.detail()).contains("firewall");
    }

    @Test
    void highCve_generatesHighFinding() {
        NetworkPort p = portWithCve(443, "https", "CVE-2023-1234", 7.5);
        NetworkHost h = host("192.168.1.30", p);

        AnalysisReport report = service.analyze("192.168.1.30", List.of(h));

        assertThat(report.findings()).hasSize(1);
        Finding f = report.findings().get(0);
        assertThat(f.severity()).isEqualTo("HIGH");
        assertThat(f.title()).containsIgnoringCase("high");
        assertThat(f.relatedCves()).contains("CVE-2023-1234");
    }

    @Test
    void criticalCve_generatesCriticalFinding() {
        NetworkPort p = portWithCve(8080, "http", "CVE-2021-44228", 10.0);
        NetworkHost h = host("192.168.1.40", p);

        AnalysisReport report = service.analyze("192.168.1.40", List.of(h));

        Finding f = report.findings().get(0);
        assertThat(f.severity()).isEqualTo("CRITICAL");
        assertThat(f.title()).containsIgnoringCase("critical");
    }

    @Test
    void cveBelow40_onlyLowExposure() {
        NetworkPort p = portWithCve(80, "http", "CVE-2020-0001", 3.1);
        NetworkHost h = host("192.168.1.50", p);

        AnalysisReport report = service.analyze("192.168.1.50", List.of(h));

        assertThat(report.findings()).hasSize(1);
        assertThat(report.findings().get(0).severity()).isEqualTo("LOW");
        assertThat(report.findings().get(0).relatedCves()).isEmpty();
    }

    @Test
    void cveFindingContainsDetail() {
        NetworkPort p = portWithCve(22, "ssh", "CVE-2023-9999", 9.8);
        NetworkHost h = host("10.0.0.5", p);

        AnalysisReport report = service.analyze("10.0.0.5", List.of(h));

        Finding f = report.findings().get(0);
        assertThat(f.detail()).contains("Device");
        assertThat(f.detail()).contains("vulnerability");
        assertThat(f.detail()).contains("CVSS");
    }

    @Test
    void multipleFindingsSortedByDescendingSeverity() {
        NetworkHost h = host("192.168.1.1",
                port(21, "open", "ftp"),
                port(2375, "open", "docker")
        );

        AnalysisReport report = service.analyze("192.168.1.1", List.of(h));

        assertThat(report.findings()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(report.findings().get(0).severity()).isEqualTo("CRITICAL");
    }

    @Test
    void ftpOpen_recommendationMentionsReplace() {
        NetworkHost h = host("192.168.1.1", port(21, "open", "ftp"));

        AnalysisReport report = service.analyze("192.168.1.1", List.of(h));

        assertThat(report.recommendations())
                .anyMatch(r -> r.contains("Replace") && r.contains("FTP") && r.contains("21"));
    }

    @Test
    void rdpOpen_recommendationMentionsFirewall() {
        NetworkHost h = host("192.168.1.1", port(3389, "open", "rdp"));

        AnalysisReport report = service.analyze("192.168.1.1", List.of(h));

        assertThat(report.recommendations())
                .anyMatch(r -> r.contains("3389") && r.contains("firewall"));
    }

    @Test
    void cvePresent_recommendationMentionsUpdate() {
        NetworkPort p = portWithCve(443, "ssl", "CVE-2023-0001", 7.0);
        NetworkHost h = host("192.168.1.1", p);

        AnalysisReport report = service.analyze("192.168.1.1", List.of(h));

        assertThat(report.recommendations())
                .anyMatch(r -> r.toLowerCase().contains("update"));
    }

    @Test
    void multipleSevereFindings_recommendsSegmentation() {
        NetworkHost h = host("192.168.1.1",
                port(21, "open", "ftp"),
                port(23, "open", "telnet"),
                port(2375, "open", "docker")
        );

        AnalysisReport report = service.analyze("192.168.1.1", List.of(h));

        assertThat(report.recommendations())
                .anyMatch(r -> r.contains("segmentation"));
    }

    @Test
    void summaryContainsText() {
        NetworkHost h = host("192.168.1.1", port(21, "open", "ftp"));

        AnalysisReport report = service.analyze("192.168.1.1", List.of(h));

        assertThat(report.summary()).contains("analysis");
        assertThat(report.summary()).contains("device");
    }

    @Test
    void summaryWithoutVulnerabilities_positiveMessage() {
        NetworkHost h = host("192.168.1.1", port(80, "open", "http"));

        AnalysisReport report = service.analyze("192.168.1.1", List.of(h));

        assertThat(report.summary()).contains("No significant");
    }

    @Test
    void spanishLocale_producesSpanishText() {
        NetworkHost h = host("192.168.1.1", port(21, "open", "ftp"));

        AnalysisReport report = service.analyze("192.168.1.1", List.of(h), new Locale("es"));

        assertThat(report.findings().get(0).detail()).contains("texto claro");
        assertThat(report.summary()).contains("El analisis");
        assertThat(report.recommendations()).anyMatch(r -> r.contains("Reemplaza"));
    }
}
