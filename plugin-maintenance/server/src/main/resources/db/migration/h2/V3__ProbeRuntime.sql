ALTER TABLE maintenance_probe_run ADD COLUMN execution_mode VARCHAR(30) DEFAULT 'static' NOT NULL;
ALTER TABLE maintenance_probe_run ADD COLUMN runtime_sha256 VARCHAR(64);
ALTER TABLE maintenance_probe_run ADD COLUMN runtime_json TEXT;
