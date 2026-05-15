package com.netsentinel.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "network_ports")
@Getter
@Setter
@NoArgsConstructor
public class NetworkPort {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private int portNumber;

    @Column(nullable = false)
    private String protocol;

    @Column(nullable = false)
    private String state;

    private String service;
    private String version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "host_id", nullable = false)
    private NetworkHost host;

    @OneToMany(mappedBy = "port", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CveEntry> cves = new ArrayList<>();
}
