CREATE TABLE maintenance_plugin_input (
    id VARCHAR(36) PRIMARY KEY,
    source_id VARCHAR(36) NOT NULL REFERENCES maintenance_source(id),
    artifact_sha256 VARCHAR(64) NOT NULL,
    request_key VARCHAR(80) NOT NULL UNIQUE,
    request_sha256 VARCHAR(64) NOT NULL,
    input_sha256 VARCHAR(64) NOT NULL,
    input_base64 TEXT NOT NULL,
    evidence_sha256 VARCHAR(64) NOT NULL,
    evidence TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY (source_id, artifact_sha256) REFERENCES maintenance_artifact(source_id, sha256)
);
CREATE INDEX idx_plugin_input_source ON maintenance_plugin_input(source_id, created_at);
