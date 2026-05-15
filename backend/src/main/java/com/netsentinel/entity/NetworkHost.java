package com.netsentinel.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "network_hosts")
@Getter
@Setter
@NoArgsConstructor
public class NetworkHost {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String ip;

    private String hostname;
    private String os;
    private String macAddress;
    private String vendor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scan_job_id", nullable = false)
    private ScanJob scanJob;

    @OneToMany(mappedBy = "host", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<NetworkPort> ports = new ArrayList<>();
}
