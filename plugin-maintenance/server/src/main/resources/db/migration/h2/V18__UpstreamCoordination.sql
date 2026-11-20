CREATE TABLE maintenance_upstream (
    source_id VARCHAR(36) PRIMARY KEY REFERENCES maintenance_source(id),
    base_sha256 VARCHAR(64) NOT NULL,
    head_sha256 VARCHAR(64) NOT NULL,
    row_version BIGINT DEFAULT 0 NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY (source_id, base_sha256) REFERENCES maintenance_artifact(source_id, sha256),
    FOREIGN KEY (source_id, head_sha256) REFERENCES maintenance_artifact(source_id, sha256)
);

CREATE TABLE maintenance_upstream_event (
    id VARCHAR(36) PRIMARY KEY,
    source_id VARCHAR(36) NOT NULL REFERENCES maintenance_source(id),
    request_key VARCHAR(80) NOT NULL,
    request_sha256 VARCHAR(64) NOT NULL,
    receipt_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (source_id, request_key)
);
CREATE INDEX idx_upstream_event_source ON maintenance_upstream_event(source_id, created_at);
