CREATE TABLE company (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO company (id, code, name, status) VALUES
    (1, 'DEV_FINFLOW', 'FINFLOW 开发企业', 'ACTIVE');

ALTER TABLE sys_user ADD COLUMN company_id BIGINT;
UPDATE sys_user SET company_id = 1 WHERE company_id IS NULL;
ALTER TABLE sys_user ADD CONSTRAINT fk_sys_user_company FOREIGN KEY (company_id) REFERENCES company(id);
CREATE INDEX idx_sys_user_company_status ON sys_user(company_id, status);

ALTER TABLE bank_account ADD COLUMN company_id BIGINT;
UPDATE bank_account SET company_id = 1 WHERE company_id IS NULL;
ALTER TABLE bank_account ADD CONSTRAINT fk_bank_account_company FOREIGN KEY (company_id) REFERENCES company(id);
CREATE INDEX idx_bank_account_company_status ON bank_account(company_id, status);

ALTER TABLE connection_profile ADD COLUMN company_id BIGINT;
UPDATE connection_profile SET company_id = 1 WHERE company_id IS NULL;
ALTER TABLE connection_profile ADD CONSTRAINT fk_connection_profile_company FOREIGN KEY (company_id) REFERENCES company(id);
CREATE INDEX idx_connection_profile_company ON connection_profile(company_id, connection_code);

ALTER TABLE connection_operation_task ADD COLUMN company_id BIGINT;
UPDATE connection_operation_task SET company_id = 1 WHERE company_id IS NULL;
ALTER TABLE connection_operation_task ADD CONSTRAINT fk_connection_task_company FOREIGN KEY (company_id) REFERENCES company(id);
CREATE INDEX idx_connection_task_company ON connection_operation_task(company_id, status, created_at, id);

ALTER TABLE connection_operation_log ADD COLUMN company_id BIGINT;
UPDATE connection_operation_log SET company_id = 1 WHERE company_id IS NULL;
ALTER TABLE connection_operation_log ADD CONSTRAINT fk_connection_log_company FOREIGN KEY (company_id) REFERENCES company(id);
CREATE INDEX idx_connection_log_company ON connection_operation_log(company_id, occurred_at, id);

CREATE TABLE bank_data_sync_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    task_no VARCHAR(64) NOT NULL UNIQUE,
    adapter_code VARCHAR(64) NOT NULL,
    connection_id BIGINT,
    bank_account_id BIGINT NOT NULL,
    requested_by BIGINT,
    request_id VARCHAR(64) NOT NULL,
    bank_request_no VARCHAR(128),
    status VARCHAR(24) NOT NULL,
    raw_count INT NOT NULL DEFAULT 0,
    normalized_count INT NOT NULL DEFAULT 0,
    duplicate_count INT NOT NULL DEFAULT 0,
    invalid_count INT NOT NULL DEFAULT 0,
    error_message VARCHAR(500),
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_bank_sync_company FOREIGN KEY (company_id) REFERENCES company(id),
    CONSTRAINT fk_bank_sync_connection FOREIGN KEY (connection_id) REFERENCES connection_profile(id),
    CONSTRAINT fk_bank_sync_account FOREIGN KEY (bank_account_id) REFERENCES bank_account(id),
    CONSTRAINT fk_bank_sync_user FOREIGN KEY (requested_by) REFERENCES sys_user(id)
);

CREATE TABLE bank_data_raw_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    adapter_code VARCHAR(64) NOT NULL,
    bank_request_no VARCHAR(128),
    content_sha256 CHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    retention_until TIMESTAMP NOT NULL,
    CONSTRAINT fk_bank_raw_company FOREIGN KEY (company_id) REFERENCES company(id),
    CONSTRAINT fk_bank_raw_task FOREIGN KEY (task_id) REFERENCES bank_data_sync_task(id)
);

CREATE TABLE bank_data_statement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    raw_message_id BIGINT NOT NULL,
    bank_account_id BIGINT NOT NULL,
    bank_request_no VARCHAR(128),
    statement_no VARCHAR(128) NOT NULL,
    transaction_time TIMESTAMP NOT NULL,
    direction VARCHAR(16) NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'CNY',
    counterparty_name VARCHAR(128),
    counterparty_account_masked VARCHAR(32),
    summary VARCHAR(255),
    validation_status VARCHAR(16) NOT NULL,
    validation_message VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_bank_statement_company FOREIGN KEY (company_id) REFERENCES company(id),
    CONSTRAINT fk_bank_statement_task FOREIGN KEY (task_id) REFERENCES bank_data_sync_task(id),
    CONSTRAINT fk_bank_statement_raw FOREIGN KEY (raw_message_id) REFERENCES bank_data_raw_message(id),
    CONSTRAINT fk_bank_statement_account FOREIGN KEY (bank_account_id) REFERENCES bank_account(id),
    CONSTRAINT uk_bank_statement_business UNIQUE (company_id, bank_account_id, statement_no, transaction_time, amount)
);

CREATE TABLE bank_data_sync_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    level VARCHAR(16) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    result VARCHAR(24) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    bank_request_no VARCHAR(128),
    message VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_bank_sync_log_company FOREIGN KEY (company_id) REFERENCES company(id),
    CONSTRAINT fk_bank_sync_log_task FOREIGN KEY (task_id) REFERENCES bank_data_sync_task(id)
);

CREATE INDEX idx_bank_sync_task_company ON bank_data_sync_task(company_id, status, created_at, id);
CREATE UNIQUE INDEX uk_bank_sync_task_request ON bank_data_sync_task(company_id, request_id);
CREATE INDEX idx_bank_raw_task ON bank_data_raw_message(company_id, task_id, received_at);
CREATE INDEX idx_bank_raw_retention ON bank_data_raw_message(retention_until);
CREATE INDEX idx_bank_statement_query ON bank_data_statement(company_id, transaction_time, id);
CREATE INDEX idx_bank_statement_account ON bank_data_statement(company_id, bank_account_id, transaction_time);
CREATE INDEX idx_bank_sync_log_query ON bank_data_sync_log(company_id, created_at, id);
CREATE INDEX idx_bank_sync_log_request ON bank_data_sync_log(company_id, request_id, created_at);

INSERT INTO sys_permission (id, code, name, description) VALUES
    (20, 'bankdata:view', '查看银行数据', 'View bank data sync tasks and normalized statements'),
    (21, 'bankdata:sync', '同步银行数据', 'Trigger simulated bank data synchronization'),
    (22, 'bankdata:reconciliation:view', '查看银行数据对账', 'View bank data reconciliation projection');

INSERT INTO sys_permission (id, code, name, description) VALUES
    (23, 'bank-sync:trigger', '触发银行同步任务', 'Trigger controlled bank synchronization jobs'),
    (24, 'bankdata:balance:view', '查看余额投影', 'View controlled balance projection'),
    (25, 'bankdata:statement:view', '查看流水投影', 'View controlled statement projection'),
    (26, 'bankdata:receipt:view', '查看回单投影', 'View controlled receipt projection'),
    (27, 'bankdata:payment:view', '查看支付投影', 'View controlled payment projection'),
    (28, 'bankdata:payroll:view', '查看代发投影', 'View controlled payroll projection');

INSERT INTO sys_role_permission (role_id, permission_id) VALUES
    (1, 20), (1, 21), (1, 22),
    (2, 20), (2, 21), (2, 22),
    (3, 20), (3, 22),
    (4, 20),
    (1, 23), (1, 24), (1, 25), (1, 26), (1, 27), (1, 28),
    (2, 23), (2, 24), (2, 25), (2, 26), (2, 27), (2, 28),
    (3, 24), (3, 25), (3, 26), (3, 27), (3, 28),
    (4, 24), (4, 25), (4, 26), (4, 27), (4, 28);
