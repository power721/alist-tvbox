ALTER TABLE maintenance_probe_run ADD COLUMN maintenance_key VARCHAR(80);
ALTER TABLE maintenance_probe_run ADD COLUMN maintenance_configuration_sha256 VARCHAR(64);
ALTER TABLE maintenance_probe_run ADD COLUMN maintenance_status VARCHAR(30);
ALTER TABLE maintenance_probe_run ADD COLUMN maintenance_repair_id VARCHAR(36);
ALTER TABLE maintenance_probe_run ADD COLUMN maintenance_stop_code VARCHAR(100);
CREATE UNIQUE INDEX maintenance_probe_workflow_key ON maintenance_probe_run(source_id, maintenance_key);
