CREATE TABLE maintenance_model_named_budget (
    model_name VARCHAR(120) PRIMARY KEY,
    budget_day DATE,
    used_calls INTEGER DEFAULT 0 NOT NULL,
    reserved_input_bytes BIGINT DEFAULT 0 NOT NULL,
    reserved_output_tokens BIGINT DEFAULT 0 NOT NULL
);
-- Model names remain exact, case-sensitive accounting identities across endpoint,
-- credential and limit changes. All accepted calls count, regardless of outcome.
-- Backfill only the existing global UTC accounting day; never rewrite old calls.
INSERT INTO maintenance_model_named_budget
    (model_name, budget_day, used_calls, reserved_input_bytes, reserved_output_tokens)
SELECT c.model_name, b.budget_day, COUNT(*), SUM(c.request_bytes), SUM(c.reserved_output_tokens)
FROM maintenance_model_call c
JOIN maintenance_model_budget b ON b.id = 1
WHERE EXTRACT(YEAR FROM c.created_at AT TIME ZONE 'UTC') = EXTRACT(YEAR FROM b.budget_day)
  AND EXTRACT(MONTH FROM c.created_at AT TIME ZONE 'UTC') = EXTRACT(MONTH FROM b.budget_day)
  AND EXTRACT(DAY FROM c.created_at AT TIME ZONE 'UTC') = EXTRACT(DAY FROM b.budget_day)
GROUP BY c.model_name, b.budget_day;
