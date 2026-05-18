package com.netsentinel.service;

import com.netsentinel.dto.AnalysisReport;
import com.netsentinel.dto.AnalysisReport.Finding;
import com.netsentinel.dto.AnalysisReport.HostSummary;
import com.netsentinel.entity.CveEntry;
import com.netsentinel.entity.NetworkHost;
import com.netsentinel.entity.NetworkPort;
import com.netsentinel.entity.WebFinding;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class AnalysisService {

    private record ServiceRisk(String level, String reason) {}

    private static final Map<Integer, ServiceRisk> KNOWN_RISKS = Map.ofEntries(
            Map.entry(21,    new ServiceRisk("MEDIUM", "FTP transmite credenciales y datos en texto claro. Una captura pasiva de red es suficiente para robar las credenciales de acceso.")),
            Map.entry(23,    new ServiceRisk("HIGH",   "Telnet es un protocolo sin cifrado ni proteccion de integridad. Todo el trafico de sesion, incluidas las contrasenas, se transmite en texto plano.")),
            Map.entry(445,   new ServiceRisk("HIGH",   "SMB es un vector frecuente de movimiento lateral y propagacion de ransomware. La exposicion a redes no confiables aumenta significativamente el riesgo.")),
            Map.entry(3389,  new ServiceRisk("HIGH",   "RDP es uno de los servicios mas atacados mediante fuerza bruta y explotacion de omisiones de autenticacion.")),
            Map.entry(5900,  new ServiceRisk("MEDIUM", "VNC puede configurarse sin autenticacion o con credenciales debiles, permitiendo acceso grafico remoto completo.")),
            Map.entry(6379,  new ServiceRisk("HIGH",   "Redis no tiene autenticacion por defecto. Un atacante con acceso a la red puede leer, escribir o eliminar todos los datos en cache y ejecutar scripts en el servidor.")),
            Map.entry(27017, new ServiceRisk("HIGH",   "MongoDB se despliega frecuentemente sin autenticacion. Una instancia accesible en la red expone todas las bases de datos a lectura y escritura.")),
            Map.entry(11211, new ServiceRisk("MEDIUM", "Memcached no tiene mecanismo de autenticacion. Las instancias expuestas pueden filtrar datos de la aplicacion en cache y ser explotadas en ataques de amplificacion.")),
            Map.entry(2375,  new ServiceRisk("CRITICAL","API TCP del daemon de Docker expuesta sin TLS. Cualquier cliente puede crear contenedores, montar el sistema de archivos del host y comprometer el sistema por completo.")),
            Map.entry(9200,  new ServiceRisk("MEDIUM", "API REST de Elasticsearch expuesta sin autenticacion. Todos los datos indexados son accesibles y el cluster puede ser modificado por cualquier persona con acceso a la red."))
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
                        "Base de datos accesible directamente en el puerto " + port.getPortNumber(),
                        buildDbExposureDetail(host.getIp(), port),
                        host.getIp(),
                        port.getPortNumber(),
                        port.getService() != null ? port.getService() : "database",
                        List.of()
                ));
            }
        }

        analyzeWebFindings(host, findings);

        String hostRisk = calculateHostRisk(openPorts, findings, host.getIp());
        summaries.add(new HostSummary(
                host.getIp(),
                hostRisk,
                openPorts.size(),
                totalCves,
                buildHostSummary(host, openPorts, totalCves)
        ));
    }

    private void analyzeWebFindings(NetworkHost host, List<Finding> findings) {
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
                    "Contenido web critico expuesto en " + host.getIp(),
                    "Se encontraron " + highFindings.size() + " ruta(s) web de alto riesgo accesibles: " + paths +
                    ". Estas rutas pueden exponer paneles de administracion, archivos de configuracion o funciones criticas del sistema.",
                    host.getIp(), 80, "http", List.of()
            ));
        } else if (!mediumFindings.isEmpty()) {
            findings.add(new Finding(
                    "MEDIUM",
                    "Contenido web sensible detectado en " + host.getIp(),
                    "Se encontraron " + mediumFindings.size() + " ruta(s) web con contenido potencialmente sensible " +
                    "detectadas por gobuster y nikto. Revisa cada hallazgo y restringe el acceso publico.",
                    host.getIp(), 80, "http", List.of()
            ));
        }
    }

    private Finding buildCveFinding(String ip, NetworkPort port, String severity, List<CveEntry> cves) {
        String service = port.getService() != null ? port.getService() : "service";
        String version = port.getVersion() != null ? " " + port.getVersion() : "";
        int count = cves.size();
        CveEntry top = cves.get(0);

        String severityEs = switch (severity) {
            case "CRITICAL" -> "critica";
            case "HIGH"     -> "alta";
            case "MEDIUM"   -> "media";
            default         -> "baja";
        };
        String title = String.format("%s%s — %d vulnerabilidad%s de severidad %s (puerto %d)",
                capitalize(service), version, count, count > 1 ? "es" : "", severityEs, port.getPortNumber());

        String desc = top.getDescription() != null ? top.getDescription() : "Sin descripcion disponible.";
        String excerpt = desc.length() > 200 ? desc.substring(0, 200) + "..." : desc;

        String detail = String.format(
                "El dispositivo %s expone %s%s en el puerto %d/%s. Se identificaron %d vulnerabilidad%s de severidad %s: %s. " +
                "La mas grave (%s, CVSS %.1f): %s",
                ip, service, version, port.getPortNumber(), port.getProtocol(),
                count, count > 1 ? "es" : "", severityEs,
                cves.stream().map(CveEntry::getCveId).collect(Collectors.joining(", ")),
                top.getCveId(), top.getCvssScore(), excerpt
        );

        List<String> cveIds = cves.stream().map(CveEntry::getCveId).toList();
        return new Finding(severity, title, detail, ip, port.getPortNumber(),
                port.getService() != null ? port.getService() : "unknown", cveIds);
    }

    private String buildServiceTitle(NetworkPort port) {
        String name = port.getService() != null ? capitalize(port.getService()) : "Servicio";
        return name + " expuesto en el puerto " + port.getPortNumber() + " — " + getServiceRiskSuffix(port.getPortNumber());
    }

    private String getServiceRiskSuffix(int port) {
        return switch (port) {
            case 21 -> "protocolo en texto claro";
            case 23 -> "acceso remoto sin cifrado";
            case 445 -> "vector de movimiento lateral";
            case 3389 -> "objetivo de ataques de fuerza bruta";
            case 5900 -> "acceso grafico sin autenticacion";
            case 6379 -> "base de datos en cache sin autenticacion";
            case 27017 -> "base de datos sin autenticacion";
            case 11211 -> "cache sin autenticacion";
            case 2375 -> "API de contenedores sin autenticacion";
            case 9200 -> "indice de busqueda sin autenticacion";
            default -> "riesgo conocido";
        };
    }

    private String buildDbExposureDetail(String ip, NetworkPort port) {
        String service = port.getService() != null ? port.getService() : "base de datos";
        return String.format(
                "El puerto %d en %s expone una instancia de %s directamente en la red. " +
                "Los servicios de base de datos no deben ser accesibles desde redes no confiables. " +
                "Se recomienda usar reglas de firewall para restringir el acceso solo a los sistemas autorizados.",
                port.getPortNumber(), ip, service);
    }

    private String buildHostSummary(NetworkHost host, List<NetworkPort> openPorts, int totalCves) {
        if (openPorts.isEmpty()) return "No se detectaron puertos abiertos en este dispositivo.";

        String services = openPorts.stream()
                .map(p -> p.getService() != null ? p.getService() : String.valueOf(p.getPortNumber()))
                .distinct().limit(4)
                .collect(Collectors.joining(", "));

        String cveInfo = totalCves > 0
                ? String.format(" Se detectaron %d vulnerabilidad%s conocida%s.", totalCves, totalCves > 1 ? "es" : "", totalCves > 1 ? "s" : "")
                : "";

        String osInfo = host.getOs() != null ? " Sistema operativo: " + host.getOs() + "." : "";

        return String.format("%d puerto%s abierto%s: %s.%s%s",
                openPorts.size(), openPorts.size() > 1 ? "s" : "", openPorts.size() > 1 ? "s" : "",
                services, osInfo, cveInfo);
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

        String hostWord = hosts.size() == 1 ? "dispositivo" : "dispositivos";
        String portWord = totalOpen == 1 ? "puerto abierto" : "puertos abiertos";

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("El analisis de %s descubrio %d %s con %d %s. ",
                target, hosts.size(), hostWord, totalOpen, portWord));

        if (critical > 0 || high > 0) {
            sb.append(String.format("Se encontraron %s: %s",
                    findings.size() == 1 ? "1 problema de seguridad" : findings.size() + " problemas de seguridad",
                    buildSeveritySummary(critical, high, medium)));
        } else if (medium > 0) {
            sb.append(String.format("Se identificaron %d problema%s de severidad media que requieren atencion.",
                    medium, medium > 1 ? "s" : ""));
        } else {
            sb.append("No se detectaron vulnerabilidades significativas.");
        }

        sb.append(" ").append(riskSentence(riskLevel));
        return sb.toString();
    }

    private String buildSeveritySummary(long critical, long high, long medium) {
        List<String> parts = new ArrayList<>();
        if (critical > 0) parts.add(critical + " critico" + (critical > 1 ? "s" : ""));
        if (high > 0) parts.add(high + " alto" + (high > 1 ? "s" : ""));
        if (medium > 0) parts.add(medium + " medio" + (medium > 1 ? "s" : ""));
        return String.join(", ", parts) + ".";
    }

    private String riskSentence(String level) {
        return switch (level) {
            case "CRITICAL" -> "Se requiere accion inmediata — el riesgo de explotacion activa es alto.";
            case "HIGH"     -> "Se recomienda tomar medidas urgentes para reducir la exposicion.";
            case "MEDIUM"   -> "Se aconseja aplicar los parches pendientes y restringir el acceso a los servicios expuestos.";
            case "LOW"      -> "La exposicion es limitada. Mantener monitoreo regular y ciclos de actualizacion.";
            default         -> "No se requiere ninguna accion inmediata.";
        };
    }

    private List<String> buildRecommendations(List<Finding> findings, List<NetworkHost> hosts) {
        Set<String> seen = new LinkedHashSet<>();

        for (Finding f : findings) {
            String svc = f.service().toLowerCase();
            int port = f.port();

            if (!f.relatedCves().isEmpty()) {
                String rec = "Actualiza " + capitalize(f.service()) + " a la ultima version estable para corregir " +
                        f.relatedCves().size() + " vulnerabilidad" + (f.relatedCves().size() > 1 ? "es" : "") +
                        " conocida" + (f.relatedCves().size() > 1 ? "s" : "") + " en el puerto " + port + ".";
                seen.add(rec);
            }

            if (port == 23) seen.add("Reemplaza Telnet (puerto 23) por SSH para todos los accesos remotos.");
            if (port == 21) seen.add("Reemplaza FTP (puerto 21) por SFTP o FTPS para proteger las credenciales en transito.");
            if (port == 3389) seen.add("Restringe el acceso a RDP (puerto 3389) mediante reglas de firewall y habilita la Autenticacion de Nivel de Red (NLA).");
            if (port == 445) seen.add("Bloquea SMB (puerto 445) en el perimetro. Habilita la firma SMB para prevenir ataques de retransmision.");
            if (port == 6379) seen.add("Habilita autenticacion en Redis (requirepass) y vincula Redis a localhost o a una interfaz privada.");
            if (port == 27017) seen.add("Habilita autenticacion en MongoDB y restringe el acceso al puerto de la base de datos por IP.");
            if (port == 2375) seen.add("Desactiva inmediatamente la API TCP de Docker o aplica autenticacion TLS de cliente.");
            if (DATABASE_PORTS.contains(port) && f.relatedCves().isEmpty()) {
                seen.add("Restringe el puerto de base de datos " + port + " (" + capitalize(f.service()) + ") solo a los servidores de aplicaciones autorizados mediante reglas de firewall.");
            }
        }

        long hasVulnerabilities = findings.stream().filter(f -> !f.relatedCves().isEmpty()).count();
        if (hasVulnerabilities > 0) {
            seen.add("Establece un calendario regular de actualizaciones — prioriza los servicios con vulnerabilidades conocidas.");
        }
        if (findings.size() > 2) {
            seen.add("Aplica segmentacion de red para limitar el movimiento lateral entre los servicios expuestos.");
        }

        long highWebCount = hosts.stream()
                .flatMap(h -> h.getWebFindings().stream())
                .filter(wf -> "HIGH".equals(wf.getSeverity()))
                .count();
        if (highWebCount > 0) {
            seen.add("Revisa y protege las rutas web de alto riesgo detectadas — restringe el acceso con autenticacion o reglas de firewall.");
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
