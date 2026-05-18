package com.netsentinel;

import com.netsentinel.repository.ScanJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@SpringBootApplication
public class NetSentinelApplication {

    private static final Logger log = LoggerFactory.getLogger(NetSentinelApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(NetSentinelApplication.class, args);
    }

    @Bean
    @Transactional
    public ApplicationRunner recoverStuckJobs(ScanJobRepository repo) {
        return args -> {
            int updated = repo.markStuckJobsAsFailed(Instant.now());
            if (updated > 0) {
                log.warn("Marked {} stuck scan job(s) as FAILED on startup", updated);
            }
        };
    }
}
