ALTER TABLE maintenance_release_plan ADD COLUMN local_receipt_json TEXT;
ALTER TABLE maintenance_release_plan DROP CONSTRAINT ck_release_plan_state;
ALTER TABLE maintenance_release_plan ADD CONSTRAINT ck_release_plan_state CHECK (
    (status = 'AWAITING_RUNTIME_VALIDATION' AND active_source_id = source_id
        AND active_source_id IS NOT NULL AND cancelled_at IS NULL AND local_receipt_json IS NULL)
    OR (status = 'CANCELLED' AND active_source_id IS NULL AND cancelled_at IS NOT NULL
        AND local_receipt_json IS NULL)
    OR (status IN ('LOCAL_APPLIED', 'LOCAL_ROLLED_BACK') AND active_source_id IS NULL
        AND cancelled_at IS NULL AND local_receipt_json IS NOT NULL)
);
