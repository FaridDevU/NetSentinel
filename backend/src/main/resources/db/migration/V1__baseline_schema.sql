CREATE TABLE IF NOT EXISTS scan_jobs (
    id UUID PRIMARY KEY,
    target VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    raw_output TEXT,
    error_message TEXT,
    ai_report TEXT,
    scan_logs TEXT,
    risk_level VARCHAR(32),
    risk_score DOUBLE PRECISION
);

CREATE TABLE IF NOT EXISTS scan_job_parameters (
    scan_job_id UUID NOT NULL REFERENCES scan_jobs(id) ON DELETE CASCADE,
    parameter VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_scan_jobs_started_at ON scan_jobs(started_at DESC);
CREATE INDEX IF NOT EXISTS idx_scan_jobs_status ON scan_jobs(status);

CREATE TABLE IF NOT EXISTS network_hosts (
    id UUID PRIMARY KEY,
    ip VARCHAR(64) NOT NULL,
    hostname VARCHAR(255),
    os VARCHAR(255),
    mac_address VARCHAR(64),
    vendor VARCHAR(255),
    scan_job_id UUID NOT NULL REFERENCES scan_jobs(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_network_hosts_scan_job ON network_hosts(scan_job_id);

CREATE TABLE IF NOT EXISTS network_ports (
    id UUID PRIMARY KEY,
    port_number INTEGER NOT NULL,
    protocol VARCHAR(16) NOT NULL,
    state VARCHAR(32) NOT NULL,
    service VARCHAR(255),
    version VARCHAR(255),
    host_id UUID NOT NULL REFERENCES network_hosts(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_network_ports_host ON network_ports(host_id);
CREATE INDEX IF NOT EXISTS idx_network_ports_state ON network_ports(state);

CREATE TABLE IF NOT EXISTS cve_entries (
    id UUID PRIMARY KEY,
    cve_id VARCHAR(64) NOT NULL,
    description TEXT,
    cvss_score DOUBLE PRECISION,
    cvss_vector VARCHAR(255),
    published_date VARCHAR(64),
    nvd_url VARCHAR(512),
    port_id UUID NOT NULL REFERENCES network_ports(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_cve_entries_port ON cve_entries(port_id);
CREATE INDEX IF NOT EXISTS idx_cve_entries_cve_id ON cve_entries(cve_id);

CREATE TABLE IF NOT EXISTS web_findings (
    id UUID PRIMARY KEY,
    host_id UUID NOT NULL REFERENCES network_hosts(id) ON DELETE CASCADE,
    tool VARCHAR(64) NOT NULL,
    url VARCHAR(1024) NOT NULL,
    status_code INTEGER,
    description TEXT NOT NULL,
    severity VARCHAR(32) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_web_findings_host ON web_findings(host_id);
CREATE INDEX IF NOT EXISTS idx_web_findings_severity ON web_findings(severity);

CREATE TABLE IF NOT EXISTS finding_statuses (
    id UUID PRIMARY KEY,
    scan_job_id UUID NOT NULL,
    finding_key VARCHAR(500) NOT NULL,
    status VARCHAR(32) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_finding_statuses_job_key UNIQUE (scan_job_id, finding_key)
);

CREATE INDEX IF NOT EXISTS idx_finding_statuses_scan_job ON finding_statuses(scan_job_id);
