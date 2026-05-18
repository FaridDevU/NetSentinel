package com.netsentinel.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "web_findings")
@Getter
@Setter
@NoArgsConstructor
public class WebFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "host_id", nullable = false)
    private NetworkHost host;

    @Column(nullable = false)
    private String tool;

    @Column(nullable = false)
    private String url;

    private Integer statusCode;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String description;

    @Column(nullable = false)
    private String severity;

    public WebFinding(NetworkHost host, String tool, String url, Integer statusCode, String description, String severity) {
        this.host = host;
        this.tool = tool;
        this.url = url;
        this.statusCode = statusCode;
        this.description = description;
        this.severity = severity;
    }
}
