package com.netsentinel;

import com.netsentinel.service.ScanService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class NetSentinelApplication {

    public static void main(String[] args) {
        SpringApplication.run(NetSentinelApplication.class, args);
    }

    @Bean
    public ApplicationRunner recoverStuckJobs(ScanService scanService) {
        return args -> scanService.recoverStuckScans();
    }
}
