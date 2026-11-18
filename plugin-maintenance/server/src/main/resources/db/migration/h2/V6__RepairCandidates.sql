CREATE TABLE maintenance_repair (
    id VARCHAR(36) PRIMARY KEY,
    source_id VARCHAR(36) NOT NULL REFERENCES maintenance_source(id),
    baseline_run_id VARCHAR(36) NOT NULL REFERENCES maintenance_probe_run(id),
    baseline_artifact_sha256 VARCHAR(64) NOT NULL,
    fault_sha256 VARCHAR(64) NOT NULL,
    frozen_json TEXT NOT NULL,
    status VARCHAR(40) NOT NULL,
    stop_code VARCHAR(100),
    used_rounds INTEGER DEFAULT 0 NOT NULL,
    active_candidate_id VARCHAR(36),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    row_version BIGINT DEFAULT 0 NOT NULL,
    CONSTRAINT uk_repair_fault UNIQUE (source_id, fault_sha256)
);
CREATE INDEX idx_repair_source ON maintenance_repair(source_id, created_at);
CREATE TABLE maintenance_repair_candidate (
    id VARCHAR(36) PRIMARY KEY,
    repair_id VARCHAR(36) NOT NULL REFERENCES maintenance_repair(id),
    round_number INTEGER NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    body TEXT NOT NULL,
    proposal_sha256 VARCHAR(64) NOT NULL,
    proposal_json TEXT NOT NULL,
    status VARCHAR(40) NOT NULL,
    error_code VARCHAR(100),
    result_json TEXT,
    failure_sha256 VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_repair_round UNIQUE (repair_id, round_number)
);
ALTER TABLE maintenance_repair ADD CONSTRAINT fk_repair_active_candidate
    FOREIGN KEY (active_candidate_id) REFERENCES maintenance_repair_candidate(id);
