CREATE TABLE validation_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    rule_code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    rule_type VARCHAR(32) NOT NULL,
    expression VARCHAR(500) NOT NULL,
    version_no INT NOT NULL DEFAULT 1,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    priority INT NOT NULL DEFAULT 100,
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_validation_rule_company FOREIGN KEY (company_id) REFERENCES company(id),
    CONSTRAINT fk_validation_rule_user FOREIGN KEY (created_by) REFERENCES sys_user(id),
    CONSTRAINT uk_validation_rule_version UNIQUE (company_id, rule_code, version_no)
);

CREATE TABLE accounting_mapping (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    mapping_code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    direction VARCHAR(16) NOT NULL,
    counterparty_keyword VARCHAR(128),
    debit_subject VARCHAR(64) NOT NULL,
    credit_subject VARCHAR(64) NOT NULL,
    voucher_template VARCHAR(128) NOT NULL,
    version_no INT NOT NULL DEFAULT 1,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_accounting_mapping_company FOREIGN KEY (company_id) REFERENCES company(id),
    CONSTRAINT fk_accounting_mapping_user FOREIGN KEY (created_by) REFERENCES sys_user(id),
    CONSTRAINT uk_accounting_mapping_version UNIQUE (company_id, mapping_code, version_no)
);

CREATE TABLE closing_period (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    period VARCHAR(7) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    total_count INT NOT NULL DEFAULT 0,
    pending_count INT NOT NULL DEFAULT 0,
    exception_count INT NOT NULL DEFAULT 0,
    unposted_count INT NOT NULL DEFAULT 0,
    confirmed_by BIGINT,
    confirmed_at TIMESTAMP,
    request_id VARCHAR(64),
    note VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_closing_period_company FOREIGN KEY (company_id) REFERENCES company(id),
    CONSTRAINT fk_closing_period_user FOREIGN KEY (confirmed_by) REFERENCES sys_user(id),
    CONSTRAINT uk_closing_period_company_period UNIQUE (company_id, period)
);

CREATE TABLE system_audit_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    actor_id BIGINT,
    action VARCHAR(64) NOT NULL,
    object_type VARCHAR(64) NOT NULL,
    object_id VARCHAR(128),
    request_id VARCHAR(64) NOT NULL,
    result VARCHAR(16) NOT NULL,
    detail VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_system_audit_company FOREIGN KEY (company_id) REFERENCES company(id),
    CONSTRAINT fk_system_audit_user FOREIGN KEY (actor_id) REFERENCES sys_user(id)
);

CREATE INDEX idx_validation_rule_query ON validation_rule(company_id, status, priority, id);
CREATE INDEX idx_accounting_mapping_query ON accounting_mapping(company_id, status, direction, id);
CREATE INDEX idx_closing_period_query ON closing_period(company_id, status, period);
CREATE INDEX idx_system_audit_query ON system_audit_event(company_id, created_at, id);
CREATE INDEX idx_system_audit_request ON system_audit_event(company_id, request_id, created_at);

INSERT INTO sys_permission (id, code, name, description) VALUES
    (34, 'validation:view', '查看校验规则', 'View validation and accounting mappings'),
    (35, 'validation:manage', '管理校验规则', 'Create and activate validation rules and mappings'),
    (36, 'closing:view', '查看结账', 'View closing period status and checks'),
    (37, 'closing:manage', '管理结账', 'Check and close accounting periods'),
    (38, 'audit:view', '查看审计中心', 'View sanitized enterprise audit events');

INSERT INTO sys_role_permission (role_id, permission_id) VALUES
    (1, 34), (1, 35), (1, 36), (1, 37), (1, 38),
    (2, 34), (2, 36),
    (3, 34), (3, 36), (3, 37), (3, 38),
    (4, 34), (4, 36);
