ALTER TABLE maintenance_model_call ADD COLUMN auto_submit BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE maintenance_model_call ADD COLUMN submission_status VARCHAR(40) DEFAULT 'NOT_REQUESTED' NOT NULL;
ALTER TABLE maintenance_model_call ADD COLUMN submission_error_code VARCHAR(100);
ALTER TABLE maintenance_model_call ADD COLUMN submission_method VARCHAR(20);
ALTER TABLE maintenance_repair_candidate ADD COLUMN model_call_id VARCHAR(36) REFERENCES maintenance_model_call(id);
ALTER TABLE maintenance_repair_candidate ADD CONSTRAINT uk_candidate_model_call UNIQUE (model_call_id);
