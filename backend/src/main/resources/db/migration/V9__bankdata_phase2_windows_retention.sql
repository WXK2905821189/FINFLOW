ALTER TABLE bank_data_sync_task ADD COLUMN sync_key VARCHAR(255);
ALTER TABLE bank_data_sync_task ADD COLUMN trigger_type VARCHAR(16) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE bank_data_sync_task ADD COLUMN window_start TIMESTAMP NULL DEFAULT NULL;
ALTER TABLE bank_data_sync_task ADD COLUMN window_end TIMESTAMP NULL DEFAULT NULL;

UPDATE bank_data_sync_task
SET trigger_type = 'MANUAL'
WHERE trigger_type IS NULL;

UPDATE bank_data_sync_task
SET sync_key = task_no
WHERE sync_key IS NULL;

CREATE UNIQUE INDEX uk_bank_sync_task_scope
    ON bank_data_sync_task(company_id, sync_key);
CREATE INDEX idx_bank_sync_task_scope_status
    ON bank_data_sync_task(company_id, sync_key, status, created_at);
CREATE INDEX idx_bank_sync_task_window
    ON bank_data_sync_task(company_id, bank_account_id, window_start, window_end, created_at);

ALTER TABLE bank_data_raw_message ADD COLUMN purged_at TIMESTAMP NULL DEFAULT NULL;
CREATE INDEX idx_bank_raw_retention_purge
    ON bank_data_raw_message(retention_until, purged_at, id);

INSERT INTO sys_permission (id, code, name, description) VALUES
    (29, 'bankdata:sync:trigger', '触发银行数据同步', 'Trigger controlled bank data synchronization after account and window confirmation');

INSERT INTO sys_role_permission (role_id, permission_id) VALUES
    (1, 29),
    (2, 29);
