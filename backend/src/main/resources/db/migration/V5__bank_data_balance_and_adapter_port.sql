CREATE TABLE bank_data_balance (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    raw_message_id BIGINT NOT NULL,
    bank_account_id BIGINT NOT NULL,
    bank_request_no VARCHAR(128),
    available_balance DECIMAL(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'CNY',
    as_of_time TIMESTAMP NOT NULL,
    validation_status VARCHAR(16) NOT NULL,
    validation_message VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_bank_balance_company FOREIGN KEY (company_id) REFERENCES company(id),
    CONSTRAINT fk_bank_balance_task FOREIGN KEY (task_id) REFERENCES bank_data_sync_task(id),
    CONSTRAINT fk_bank_balance_raw FOREIGN KEY (raw_message_id) REFERENCES bank_data_raw_message(id),
    CONSTRAINT fk_bank_balance_account FOREIGN KEY (bank_account_id) REFERENCES bank_account(id),
    CONSTRAINT uk_bank_balance_snapshot UNIQUE (company_id, bank_account_id, as_of_time)
);

CREATE INDEX idx_bank_balance_query ON bank_data_balance(company_id, bank_account_id, as_of_time, id);
