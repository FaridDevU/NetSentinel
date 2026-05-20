package com.netsentinel.entity;

import com.netsentinel.enums.VulnStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "finding_statuses", uniqueConstraints = @UniqueConstraint(columnNames = {"scan_job_id", "finding_key"}))
@Getter
@Setter
@NoArgsConstructor
public class FindingStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "scan_job_id", nullable = false)
    private UUID scanJobId;

    @Column(name = "finding_key", nullable = false, length = 500)
    private String findingKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VulnStatus status = VulnStatus.OPEN;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public FindingStatus(UUID scanJobId, String findingKey) {
        this.scanJobId = scanJobId;
        this.findingKey = findingKey;
    }
}
