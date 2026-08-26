CREATE TABLE statement_import_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no VARCHAR(64) NOT NULL UNIQUE,
    source_type VARCHAR(16) NOT NULL,
    source_name VARCHAR(128),
    status VARCHAR(24) NOT NULL,
    total_count INT NOT NULL DEFAULT 0,
    imported_count INT NOT NULL DEFAULT 0,
    duplicate_count INT NOT NULL DEFAULT 0,
    invalid_count INT NOT NULL DEFAULT 0,
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    error_message VARCHAR(500),
    CONSTRAINT fk_statement_batch_user FOREIGN KEY (created_by) REFERENCES sys_user(id)
);

CREATE TABLE statement_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    statement_no VARCHAR(128) NOT NULL UNIQUE,
    bank_account_id BIGINT,
    transaction_time TIMESTAMP,
    direction VARCHAR(16),
    amount DECIMAL(19, 2),
    currency VARCHAR(3) DEFAULT 'CNY',
    counterparty_name VARCHAR(128),
    counterparty_account VARCHAR(128),
    summary VARCHAR(255),
    raw_payload TEXT NOT NULL,
    validation_status VARCHAR(16) NOT NULL,
    validation_message VARCHAR(500),
    review_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    review_comment VARCHAR(500),
    reviewed_by BIGINT,
    reviewed_at TIMESTAMP,
    push_status VARCHAR(16) NOT NULL DEFAULT 'NOT_PUSHED',
    voucher_no VARCHAR(128),
    push_message VARCHAR(500),
    pushed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_statement_batch FOREIGN KEY (batch_id) REFERENCES statement_import_batch(id),
    CONSTRAINT fk_statement_account FOREIGN KEY (bank_account_id) REFERENCES bank_account(id),
    CONSTRAINT fk_statement_reviewer FOREIGN KEY (reviewed_by) REFERENCES sys_user(id)
);

CREATE TABLE statement_audit_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    statement_id BIGINT NOT NULL,
    batch_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    result VARCHAR(16) NOT NULL,
    previous_status VARCHAR(64),
    current_status VARCHAR(64),
    operator_id BIGINT,
    detail VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_statement_audit_statement FOREIGN KEY (statement_id) REFERENCES statement_record(id),
    CONSTRAINT fk_statement_audit_batch FOREIGN KEY (batch_id) REFERENCES statement_import_batch(id),
    CONSTRAINT fk_statement_audit_user FOREIGN KEY (operator_id) REFERENCES sys_user(id)
);

CREATE INDEX idx_statement_batch_status ON statement_import_batch(status, created_at);
CREATE INDEX idx_statement_record_batch ON statement_record(batch_id, id);
CREATE INDEX idx_statement_record_review ON statement_record(review_status, validation_status);
CREATE INDEX idx_statement_record_time ON statement_record(transaction_time, id);
CREATE INDEX idx_statement_audit_record ON statement_audit_event(statement_id, created_at);

INSERT INTO sys_permission (id, code, name, description) VALUES
    (10, 'statement:view', '查看银行流水', 'View imported bank statements'),
    (11, 'statement:import', '导入银行流水', 'Import bank statements'),
    (12, 'statement:review', '复核银行流水', 'Review imported bank statements'),
    (13, 'voucher:push', '推送金蝶凭证', 'Push reviewed statements to Kingdee'),
    (14, 'reconciliation:view', '查看对账汇总', 'View reconciliation dashboard');

INSERT INTO sys_role_permission (role_id, permission_id) VALUES
    (1, 10), (1, 11), (1, 12), (1, 13), (1, 14),
    (2, 10), (2, 11), (2, 13), (2, 14),
    (3, 10), (3, 12), (3, 14),
    (4, 10), (4, 14);
