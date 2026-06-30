package com.netsentinel;

import com.netsentinel.config.RiskCatalogProperties;
import com.netsentinel.service.ScanService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
@EnableConfigurationProperties(RiskCatalogProperties.class)
public class NetSentinelApplication {

    public static void main(String[] args) {
        ensureDataDirectory();
        SpringApplication.run(NetSentinelApplication.class, args);
    }

    private static void ensureDataDirectory() {
        String configured = System.getenv("NETSENTINEL_DB");
        Path dbPath = (configured != null && !configured.isBlank())
                ? Path.of(configured)
                : Path.of(System.getProperty("user.home"), ".netsentinel", "netsentinel.db");
        Path parent = dbPath.getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create the database directory: " + parent, e);
        }
    }

    @Bean
    public ApplicationRunner recoverStuckJobs(ScanService scanService) {
        return args -> scanService.recoverStuckScans();
    }
}
