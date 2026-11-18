ALTER TABLE maintenance_repair_candidate ADD COLUMN source_import_id VARCHAR(36)
    REFERENCES maintenance_source_import(id);
CREATE UNIQUE INDEX ux_maintenance_candidate_import ON maintenance_repair_candidate(repair_id, source_import_id);
