CREATE TABLE maintenance_probe_schedule (
    source_id VARCHAR(36) PRIMARY KEY REFERENCES maintenance_source(id),
    sha256 VARCHAR(64) NOT NULL,
    policy_json TEXT NOT NULL,
    enabled BOOLEAN NOT NULL,
    next_due_at TIMESTAMP WITH TIME ZONE NOT NULL,
    budget_day DATE NOT NULL,
    used_attempts INTEGER DEFAULT 0 NOT NULL,
    consecutive_failures INTEGER DEFAULT 0 NOT NULL,
    suspended BOOLEAN DEFAULT FALSE NOT NULL,
    suspension_reason VARCHAR(100),
    active_run_id VARCHAR(36) REFERENCES maintenance_probe_run(id),
    last_run_id VARCHAR(36) REFERENCES maintenance_probe_run(id),
    last_outcome VARCHAR(100),
    row_version BIGINT DEFAULT 0 NOT NULL
);
CREATE INDEX idx_schedule_due ON maintenance_probe_schedule(enabled, suspended, next_due_at);
CREATE TABLE maintenance_schedule_budget (
    id INTEGER PRIMARY KEY,
    budget_day DATE NOT NULL,
    used_attempts INTEGER DEFAULT 0 NOT NULL,
    row_version BIGINT DEFAULT 0 NOT NULL
);
INSERT INTO maintenance_schedule_budget(id, budget_day) VALUES (1, DATE '1970-01-01');
ALTER TABLE maintenance_probe_run ADD COLUMN trigger_kind VARCHAR(20) DEFAULT 'manual' NOT NULL;
ALTER TABLE maintenance_probe_run ADD COLUMN schedule_sha256 VARCHAR(64);
ALTER TABLE maintenance_probe_run ADD COLUMN schedule_json TEXT;
ALTER TABLE maintenance_probe_run ADD COLUMN schedule_budget_day DATE;
ALTER TABLE maintenance_probe_run ADD COLUMN schedule_ordinal INTEGER;
