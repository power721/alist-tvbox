CREATE TABLE maintenance_release_plan (
    id VARCHAR(36) PRIMARY KEY,
    source_id VARCHAR(36) NOT NULL REFERENCES maintenance_source(id),
    candidate_id VARCHAR(36) NOT NULL REFERENCES maintenance_repair_candidate(id),
    request_key VARCHAR(80) NOT NULL,
    request_sha256 VARCHAR(64) NOT NULL,
    baseline_artifact_sha256 VARCHAR(64) NOT NULL,
    candidate_sha256 VARCHAR(64) NOT NULL,
    frozen_sha256 VARCHAR(64) NOT NULL,
    frozen_json TEXT NOT NULL,
    status VARCHAR(40) NOT NULL,
    active_source_id VARCHAR(36) REFERENCES maintenance_source(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    cancelled_at TIMESTAMP WITH TIME ZONE,
    row_version BIGINT DEFAULT 0 NOT NULL,
    CONSTRAINT uk_release_plan_request UNIQUE (source_id, request_key),
    CONSTRAINT uk_release_plan_active UNIQUE (active_source_id),
    CONSTRAINT ck_release_plan_state CHECK (
        (status = 'AWAITING_RUNTIME_VALIDATION' AND active_source_id = source_id
            AND active_source_id IS NOT NULL AND cancelled_at IS NULL)
        OR (status = 'CANCELLED' AND active_source_id IS NULL AND cancelled_at IS NOT NULL)
    )
);
CREATE INDEX idx_release_plan_source ON maintenance_release_plan(source_id, created_at);
