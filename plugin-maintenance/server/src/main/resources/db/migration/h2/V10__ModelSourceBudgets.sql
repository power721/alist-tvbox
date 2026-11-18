CREATE TABLE maintenance_model_source_budget (
    source_id VARCHAR(36) PRIMARY KEY REFERENCES maintenance_source(id),
    budget_day DATE,
    used_calls INTEGER DEFAULT 0 NOT NULL,
    reserved_input_bytes BIGINT DEFAULT 0 NOT NULL,
    reserved_output_tokens BIGINT DEFAULT 0 NOT NULL
);
-- Retain all accepted reservations in the existing global UTC accounting day,
-- including cancelled, interrupted and failed calls. Never infer past limits.
INSERT INTO maintenance_model_source_budget
    (source_id, budget_day, used_calls, reserved_input_bytes, reserved_output_tokens)
SELECT r.source_id, b.budget_day, COUNT(*), SUM(c.request_bytes), SUM(c.reserved_output_tokens)
FROM maintenance_model_call c
JOIN maintenance_repair r ON r.id = c.repair_id
JOIN maintenance_model_budget b ON b.id = 1
-- H2's timestamp-with-zone -> DATE cast uses the session zone even after
-- AT TIME ZONE. Extract UTC calendar fields instead.
WHERE EXTRACT(YEAR FROM c.created_at AT TIME ZONE 'UTC') = EXTRACT(YEAR FROM b.budget_day)
  AND EXTRACT(MONTH FROM c.created_at AT TIME ZONE 'UTC') = EXTRACT(MONTH FROM b.budget_day)
  AND EXTRACT(DAY FROM c.created_at AT TIME ZONE 'UTC') = EXTRACT(DAY FROM b.budget_day)
GROUP BY r.source_id, b.budget_day;

ALTER TABLE maintenance_model_call ADD COLUMN budget_json TEXT;
