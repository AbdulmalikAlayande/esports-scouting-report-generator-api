CREATE TABLE IF NOT EXISTS report_raw_payloads (
    id BIGSERIAL PRIMARY KEY,
    report_request_id BIGINT NOT NULL REFERENCES report_requests(id) ON DELETE CASCADE,
    payload_key VARCHAR(64) NOT NULL,
    source_stage VARCHAR(32) NOT NULL DEFAULT 'INGESTING',
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    last_modified_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_report_raw_payloads_request_key UNIQUE (report_request_id, payload_key)
);

CREATE TABLE IF NOT EXISTS report_normalized_payloads (
    id BIGSERIAL PRIMARY KEY,
    report_request_id BIGINT NOT NULL REFERENCES report_requests(id) ON DELETE CASCADE,
    payload_key VARCHAR(64) NOT NULL,
    source_stage VARCHAR(32) NOT NULL DEFAULT 'INGESTING',
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    last_modified_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_report_normalized_payloads_request_key UNIQUE (report_request_id, payload_key)
);

CREATE TABLE IF NOT EXISTS report_feature_payloads (
    id BIGSERIAL PRIMARY KEY,
    report_request_id BIGINT NOT NULL REFERENCES report_requests(id) ON DELETE CASCADE,
    payload_key VARCHAR(64) NOT NULL,
    feature_version VARCHAR(64) NOT NULL DEFAULT 'features-v1',
    source_stage VARCHAR(32) NOT NULL DEFAULT 'FEATURIZING',
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    last_modified_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_report_feature_payloads_request_key UNIQUE (report_request_id, payload_key)
);

CREATE TABLE IF NOT EXISTS report_artifacts (
    id BIGSERIAL PRIMARY KEY,
    report_request_id BIGINT NOT NULL REFERENCES report_requests(id) ON DELETE CASCADE,
    report_type VARCHAR(64) NOT NULL DEFAULT 'full',
    contract_version VARCHAR(32) NOT NULL DEFAULT 'scouting-report.v1',
    model_version VARCHAR(64),
    feature_version VARCHAR(64),
    summary TEXT,
    report_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    generated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    last_modified_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_report_artifacts_request UNIQUE (report_request_id)
);

CREATE INDEX IF NOT EXISTS idx_report_raw_payloads_request_id
    ON report_raw_payloads(report_request_id);

CREATE INDEX IF NOT EXISTS idx_report_normalized_payloads_request_id
    ON report_normalized_payloads(report_request_id);

CREATE INDEX IF NOT EXISTS idx_report_feature_payloads_request_id
    ON report_feature_payloads(report_request_id);

CREATE INDEX IF NOT EXISTS idx_report_feature_payloads_version
    ON report_feature_payloads(feature_version);

CREATE INDEX IF NOT EXISTS idx_report_artifacts_type_generated_at
    ON report_artifacts(report_type, generated_at DESC);

CREATE INDEX IF NOT EXISTS idx_report_raw_payloads_payload
    ON report_raw_payloads USING gin(payload);

CREATE INDEX IF NOT EXISTS idx_report_normalized_payloads_payload
    ON report_normalized_payloads USING gin(payload);

CREATE INDEX IF NOT EXISTS idx_report_feature_payloads_payload
    ON report_feature_payloads USING gin(payload);

CREATE INDEX IF NOT EXISTS idx_report_artifacts_report_json
    ON report_artifacts USING gin(report_json);
