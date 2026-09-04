ALTER TABLE bank_data_sync_task ADD COLUMN mapping_version VARCHAR(64) NOT NULL DEFAULT 'LEGACY_V1';
ALTER TABLE bank_data_raw_message ADD COLUMN mapping_version VARCHAR(64) NOT NULL DEFAULT 'LEGACY_V1';

UPDATE bank_data_sync_task
SET mapping_version = 'LEGACY_V1'
WHERE mapping_version IS NULL OR mapping_version = '';

UPDATE bank_data_raw_message
SET mapping_version = 'LEGACY_V1'
WHERE mapping_version IS NULL OR mapping_version = '';

CREATE INDEX idx_bank_sync_task_mapping_version
    ON bank_data_sync_task(company_id, mapping_version, created_at);
CREATE INDEX idx_bank_raw_mapping_version
    ON bank_data_raw_message(company_id, mapping_version, received_at);
