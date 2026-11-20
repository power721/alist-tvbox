-- Amount reservations are nullable because pre-V16 calls and calls without
-- configured pricing have no defensible maximum-cost snapshot.
ALTER TABLE maintenance_model_budget ADD COLUMN reserved_cost_usd DECIMAL(38,18);
ALTER TABLE maintenance_model_source_budget ADD COLUMN reserved_cost_usd DECIMAL(38,18);
ALTER TABLE maintenance_model_named_budget ADD COLUMN reserved_cost_usd DECIMAL(38,18);
ALTER TABLE maintenance_model_budget ADD COLUMN cost_breach_day DATE;
