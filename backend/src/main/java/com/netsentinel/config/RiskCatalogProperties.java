package com.netsentinel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ConfigurationProperties(prefix = "netsentinel.risks")
public class RiskCatalogProperties {

    private List<ServiceRisk> services = List.of();
    private Set<Integer> databasePorts = Set.of();

    public List<ServiceRisk> getServices() {
        return services;
    }

    public void setServices(List<ServiceRisk> services) {
        this.services = services;
    }

    public Set<Integer> getDatabasePorts() {
        return databasePorts;
    }

    public void setDatabasePorts(Set<Integer> databasePorts) {
        this.databasePorts = databasePorts;
    }

    public Map<Integer, ServiceRisk> asMap() {
        return services.stream().collect(Collectors.toUnmodifiableMap(
                ServiceRisk::getPort,
                s -> s,
                (a, b) -> a
        ));
    }

    public static class ServiceRisk {
        private int port;
        private String level;
        private String reason;
        private String shortLabel;

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getLevel() { return level; }
        public void setLevel(String level) { this.level = level; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        public String getShortLabel() { return shortLabel; }
        public void setShortLabel(String shortLabel) { this.shortLabel = shortLabel; }
    }
}
