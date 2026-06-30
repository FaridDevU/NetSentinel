package com.netsentinel.service;

import com.netsentinel.entity.ScanJob;
import com.netsentinel.enums.ScanStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ScanServiceIntegrationTest {

    private static final Path DB_FILE;

    static {
        try {
            DB_FILE = Files.createTempFile("netsentinel-it-", ".db");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB_FILE.toString().replace('\\', '/'));
    }

    @Autowired
    private ScanService scanService;

    @MockBean
    private SandboxService sandboxService;

    @MockBean
    private NvdService nvdService;

    @Test
    void schemaIsCreatedAndScanCrudWorks() {
        ScanJob job = scanService.createScan("192.168.1.0/24", List.of("-sV", "-T4"));
        assertThat(job.getId()).isNotNull();

        var status = scanService.getStatus(job.getId());
        assertThat(status).isPresent();
        assertThat(status.get().status()).isEqualTo(ScanStatus.PENDING);
        assertThat(status.get().target()).isEqualTo("192.168.1.0/24");

        var history = scanService.getHistory(0, 10);
        assertThat(history.content()).extracting(s -> s.id()).contains(job.getId());

        scanService.deleteScan(job.getId());
        assertThat(scanService.getStatus(job.getId())).isEmpty();
    }
}
