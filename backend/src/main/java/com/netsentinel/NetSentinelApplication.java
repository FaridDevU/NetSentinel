package com.netsentinel;

import com.netsentinel.config.RiskCatalogProperties;
import com.netsentinel.service.ScanService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(RiskCatalogProperties.class)
public class NetSentinelApplication {

    public static void main(String[] args) {
        SpringApplication.run(NetSentinelApplication.class, args);
    }

    @Bean
    public ApplicationRunner recoverStuckJobs(ScanService scanService) {
        return args -> scanService.recoverStuckScans();
    }
}
