CREATE TABLE maintenance_source (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    external_id VARCHAR(200) UNIQUE,
    filename VARCHAR(200) NOT NULL,
    origin_url VARCHAR(2000),
    current_artifact_id VARCHAR(36),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE maintenance_artifact (
    id VARCHAR(36) PRIMARY KEY,
    source_id VARCHAR(36) NOT NULL REFERENCES maintenance_source(id),
    sha256 VARCHAR(64) NOT NULL,
    parent_sha256 VARCHAR(64),
    body TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_maintenance_artifact UNIQUE (source_id, sha256)
);

CREATE TABLE maintenance_probe_run (
    id VARCHAR(36) PRIMARY KEY,
    source_id VARCHAR(36) NOT NULL REFERENCES maintenance_source(id),
    artifact_id VARCHAR(36) NOT NULL REFERENCES maintenance_artifact(id),
    artifact_sha256 VARCHAR(64) NOT NULL,
    inspector_sha256 VARCHAR(64) NOT NULL,
    knowledge_sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE,
    result_json TEXT,
    context_json TEXT,
    error_code VARCHAR(100)
);
CREATE INDEX idx_maintenance_probe_source ON maintenance_probe_run(source_id, created_at);
