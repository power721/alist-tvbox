CREATE TABLE maintenance_source_import (
    id VARCHAR(36) PRIMARY KEY,
    source_id VARCHAR(36) NOT NULL REFERENCES maintenance_source(id),
    request_key VARCHAR(80) NOT NULL UNIQUE,
    request_sha256 VARCHAR(64) NOT NULL,
    archive_sha256 VARCHAR(64) NOT NULL,
    archive_base64 TEXT NOT NULL,
    original_source_id VARCHAR(36) NOT NULL,
    artifact_sha256 VARCHAR(64) NOT NULL,
    original_profile_sha256 VARCHAR(64) NOT NULL,
    restored_profile_sha256 VARCHAR(64) NOT NULL,
    config_rebind_required BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_source_import_source ON maintenance_source_import(source_id, created_at);
