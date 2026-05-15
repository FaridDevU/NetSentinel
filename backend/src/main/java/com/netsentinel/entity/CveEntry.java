package com.netsentinel.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "cve_entries")
@Getter
@Setter
@NoArgsConstructor
public class CveEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String cveId;

    @Column(columnDefinition = "TEXT")
    private String description;

    private Double cvssScore;
    private String cvssVector;
    private String publishedDate;
    private String nvdUrl;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "port_id", nullable = false)
    private NetworkPort port;
}
