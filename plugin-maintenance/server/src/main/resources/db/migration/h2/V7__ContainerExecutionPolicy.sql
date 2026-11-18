ALTER TABLE maintenance_probe_run ADD COLUMN execution_policy_sha256 VARCHAR(64);
ALTER TABLE maintenance_probe_run ADD COLUMN execution_policy_json TEXT;
