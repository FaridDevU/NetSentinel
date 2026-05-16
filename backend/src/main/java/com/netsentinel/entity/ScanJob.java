package com.netsentinel.entity;

import com.netsentinel.enums.ScanStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "scan_jobs")
@Getter
@Setter
@NoArgsConstructor
public class ScanJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String target;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "scan_job_parameters", joinColumns = @JoinColumn(name = "scan_job_id"))
    @Column(name = "parameter")
    private List<String> parameters = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScanStatus status = ScanStatus.PENDING;

    @Column(nullable = false, updatable = false)
    private Instant startedAt = Instant.now();

    private Instant completedAt;

    @Column(columnDefinition = "TEXT")
    private String rawOutput;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(columnDefinition = "TEXT")
    private String aiReport;

    @Column(name = "scan_logs", columnDefinition = "TEXT")
    private String scanLogs;

    @OneToMany(mappedBy = "scanJob", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<NetworkHost> hosts = new ArrayList<>();

    public ScanJob(String target, List<String> parameters) {
        this.target = target;
        this.parameters = parameters;
    }
}
