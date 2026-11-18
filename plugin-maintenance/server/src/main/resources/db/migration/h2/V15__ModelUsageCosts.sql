-- Historical usage stays intact. There were no frozen historical prices,
-- so no historical monetary amount can be inferred from today's settings.
ALTER TABLE maintenance_model_call ADD COLUMN cost_json TEXT;
CREATE INDEX idx_model_call_usage_day ON maintenance_model_call(model_name, created_at);
