CREATE TABLE maintenance_at_publication (
    id VARCHAR(36) PRIMARY KEY,
    request_key VARCHAR(80) NOT NULL UNIQUE,
    request_sha256 VARCHAR(64) NOT NULL,
    candidate_id VARCHAR(36) NOT NULL,
    instance_key VARCHAR(64) NOT NULL,
    mode VARCHAR(20) NOT NULL,
    original_plugin_id INTEGER NOT NULL,
    published_plugin_id INTEGER,
    target_path TEXT NOT NULL,
    before_plugin_json TEXT NOT NULL,
    plugin_name VARCHAR(200) NOT NULL,
    delivery_version VARCHAR(50) NOT NULL,
    candidate_sha256 VARCHAR(64) NOT NULL,
    baseline_sha256 VARCHAR(64) NOT NULL,
    state VARCHAR(30) NOT NULL,
    error_code VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX maintenance_at_publication_candidate ON maintenance_at_publication(candidate_id, created_at);
