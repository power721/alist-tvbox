CREATE TABLE maintenance_automation_task (
    id VARCHAR(80) PRIMARY KEY,
    kind VARCHAR(30) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    source_id VARCHAR(36) NOT NULL,
    state VARCHAR(40) NOT NULL,
    context_json TEXT NOT NULL,
    error_code VARCHAR(255),
    attempts INTEGER NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX maintenance_automation_due ON maintenance_automation_task(kind, state, next_attempt_at);
