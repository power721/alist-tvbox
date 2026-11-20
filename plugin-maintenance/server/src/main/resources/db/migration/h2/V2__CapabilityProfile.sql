CREATE TABLE maintenance_profile (
    id VARCHAR(36) PRIMARY KEY,
    source_id VARCHAR(36) NOT NULL REFERENCES maintenance_source(id),
    sha256 VARCHAR(64) NOT NULL,
    snapshot_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_maintenance_profile UNIQUE (source_id, sha256)
);
ALTER TABLE maintenance_source ADD COLUMN current_profile_id VARCHAR(36);
ALTER TABLE maintenance_source ADD COLUMN config_fingerprint_salt VARCHAR(64);
ALTER TABLE maintenance_probe_run ADD COLUMN profile_sha256 VARCHAR(64);
ALTER TABLE maintenance_probe_run ADD COLUMN profile_json TEXT;
