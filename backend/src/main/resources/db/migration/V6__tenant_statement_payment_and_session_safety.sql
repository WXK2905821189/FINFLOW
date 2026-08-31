-- Keep V1-V5 immutable. Existing records belong to the development company until migrated by a tenant onboarding process.
ALTER TABLE statement_import_batch ADD COLUMN company_id BIGINT;
UPDATE statement_import_batch SET company_id = 1 WHERE company_id IS NULL;
ALTER TABLE statement_import_batch MODIFY company_id BIGINT NOT NULL;
ALTER TABLE statement_import_batch ADD CONSTRAINT fk_statement_batch_company FOREIGN KEY (company_id) REFERENCES company(id);
CREATE INDEX idx_statement_batch_company_status ON statement_import_batch(company_id, status, created_at);

ALTER TABLE statement_record ADD COLUMN company_id BIGINT;
UPDATE statement_record SET company_id = 1 WHERE company_id IS NULL;
ALTER TABLE statement_record MODIFY company_id BIGINT NOT NULL;
ALTER TABLE statement_record ADD CONSTRAINT fk_statement_record_company FOREIGN KEY (company_id) REFERENCES company(id);
ALTER TABLE statement_record ADD CONSTRAINT uk_statement_record_company_no UNIQUE (company_id, statement_no);
CREATE INDEX idx_statement_record_company_time ON statement_record(company_id, transaction_time, id);

ALTER TABLE statement_audit_event ADD COLUMN company_id BIGINT;
UPDATE statement_audit_event SET company_id = 1 WHERE company_id IS NULL;
ALTER TABLE statement_audit_event MODIFY company_id BIGINT NOT NULL;
ALTER TABLE statement_audit_event ADD CONSTRAINT fk_statement_audit_company FOREIGN KEY (company_id) REFERENCES company(id);
CREATE INDEX idx_statement_audit_company_record ON statement_audit_event(company_id, statement_id, created_at);

CREATE TABLE payment_transfer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    payment_no VARCHAR(64) NOT NULL UNIQUE,
    idempotency_key VARCHAR(96) NOT NULL,
    payer_account_id BIGINT NOT NULL,
    bank_code VARCHAR(32) NOT NULL,
    payee_name VARCHAR(128) NOT NULL,
    payee_account VARCHAR(128) NOT NULL,
    payee_account_masked VARCHAR(128) NOT NULL,
    payee_bank VARCHAR(128) NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'CNY',
    remark VARCHAR(255) NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_by BIGINT NOT NULL,
    approved_by BIGINT,
    approved_at TIMESTAMP,
    executed_by BIGINT,
    executed_at TIMESTAMP,
    external_reference VARCHAR(128),
    external_status VARCHAR(32),
    error_message VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_payment_company FOREIGN KEY (company_id) REFERENCES company(id),
    CONSTRAINT fk_payment_payer FOREIGN KEY (payer_account_id) REFERENCES bank_account(id),
    CONSTRAINT fk_payment_creator FOREIGN KEY (created_by) REFERENCES sys_user(id),
    CONSTRAINT fk_payment_approver FOREIGN KEY (approved_by) REFERENCES sys_user(id),
    CONSTRAINT fk_payment_executor FOREIGN KEY (executed_by) REFERENCES sys_user(id),
    CONSTRAINT uk_payment_company_idempotency UNIQUE (company_id, idempotency_key)
);

CREATE TABLE payment_transfer_audit_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    payment_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    previous_status VARCHAR(24),
    current_status VARCHAR(24) NOT NULL,
    operator_id BIGINT,
    detail VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_payment_audit_company FOREIGN KEY (company_id) REFERENCES company(id),
    CONSTRAINT fk_payment_audit_payment FOREIGN KEY (payment_id) REFERENCES payment_transfer(id),
    CONSTRAINT fk_payment_audit_operator FOREIGN KEY (operator_id) REFERENCES sys_user(id)
);
CREATE INDEX idx_payment_transfer_company_status ON payment_transfer(company_id, status, created_at, id);
CREATE INDEX idx_payment_transfer_payer_status ON payment_transfer(company_id, payer_account_id, status);
CREATE INDEX idx_payment_audit_company_payment ON payment_transfer_audit_event(company_id, payment_id, created_at);

ALTER TABLE sys_user ADD COLUMN token_version INT NOT NULL DEFAULT 0;

CREATE TABLE auth_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_id VARCHAR(64) NOT NULL UNIQUE,
    token_version INT NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_auth_session_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
);
CREATE INDEX idx_auth_session_active ON auth_session(user_id, token_version, expires_at, revoked_at);
