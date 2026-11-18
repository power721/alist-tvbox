CREATE TABLE maintenance_model_consent (
    source_id VARCHAR(36) PRIMARY KEY REFERENCES maintenance_source(id),
    artifact_sha256 VARCHAR(64)
);
CREATE TABLE maintenance_model_call (
    id VARCHAR(36) PRIMARY KEY,
    repair_id VARCHAR(36) NOT NULL REFERENCES maintenance_repair(id),
    request_key VARCHAR(80) NOT NULL,
    attempt_number INTEGER NOT NULL,
    configuration_sha256 VARCHAR(64) NOT NULL,
    model_name VARCHAR(120) NOT NULL,
    request_sha256 VARCHAR(64) NOT NULL,
    request_json TEXT NOT NULL,
    request_bytes INTEGER NOT NULL,
    reserved_output_tokens INTEGER NOT NULL,
    status VARCHAR(40) NOT NULL,
    error_code VARCHAR(100),
    usage_json TEXT,
    proposal_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_model_request UNIQUE (repair_id, request_key),
    CONSTRAINT uk_model_attempt UNIQUE (repair_id, attempt_number)
);
CREATE TABLE maintenance_model_budget (
    id INTEGER PRIMARY KEY,
    budget_day DATE,
    used_calls INTEGER DEFAULT 0 NOT NULL,
    reserved_input_bytes BIGINT DEFAULT 0 NOT NULL,
    reserved_output_tokens BIGINT DEFAULT 0 NOT NULL,
    active_call_id VARCHAR(36) REFERENCES maintenance_model_call(id)
);
INSERT INTO maintenance_model_budget(id) VALUES (1);
