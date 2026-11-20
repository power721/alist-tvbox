ALTER TABLE maintenance_repair ADD COLUMN automatic_status VARCHAR(40) NOT NULL DEFAULT 'DISABLED';
ALTER TABLE maintenance_repair ADD COLUMN automatic_configuration_sha256 VARCHAR(64);
ALTER TABLE maintenance_repair ADD COLUMN automatic_stop_code VARCHAR(100);
