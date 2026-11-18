CREATE TABLE maintenance_media_budget (
    id VARCHAR(50) PRIMARY KEY,
    budget_day DATE,
    used_probes INT NOT NULL,
    reserved_requests BIGINT NOT NULL,
    reserved_body_bytes BIGINT NOT NULL,
    next_due_at TIMESTAMP WITH TIME ZONE,
    last_observed_at TIMESTAMP WITH TIME ZONE
);
INSERT INTO maintenance_media_budget (id, used_probes, reserved_requests, reserved_body_bytes)
VALUES ('global', 0, 0, 0);

CREATE TABLE maintenance_media_host_budget (
    host_sha256 VARCHAR(64) PRIMARY KEY,
    last_run_id VARCHAR(36) NOT NULL,
    next_due_at TIMESTAMP WITH TIME ZONE NOT NULL
);
ALTER TABLE maintenance_media_probe ADD COLUMN budget_json TEXT;
ALTER TABLE maintenance_media_probe ADD COLUMN issued_requests INT NOT NULL DEFAULT 0;
