CREATE TABLE maintenance_at_binding (
    source_id VARCHAR(36) PRIMARY KEY REFERENCES maintenance_source(id),
    instance_key VARCHAR(64) NOT NULL,
    plugin_id INTEGER NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    snapshot_json TEXT NOT NULL,
    row_version BIGINT DEFAULT 0 NOT NULL,
    CONSTRAINT uq_at_plugin_binding UNIQUE (instance_key, plugin_id)
);
ALTER TABLE maintenance_probe_run ADD COLUMN binding_sha256 VARCHAR(64);
ALTER TABLE maintenance_probe_run ADD COLUMN binding_json TEXT;
