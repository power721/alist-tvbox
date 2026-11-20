CREATE TABLE maintenance_media_probe (
    id VARCHAR(36) PRIMARY KEY,
    source_id VARCHAR(36) NOT NULL REFERENCES maintenance_source(id),
    artifact_sha256 VARCHAR(64) NOT NULL,
    profile_sha256 VARCHAR(64) NOT NULL,
    policy_sha256 VARCHAR(64) NOT NULL,
    policy_json TEXT NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE,
    error_code VARCHAR(100),
    result_json TEXT
);
CREATE INDEX idx_media_probe_source ON maintenance_media_probe(source_id, created_at);
