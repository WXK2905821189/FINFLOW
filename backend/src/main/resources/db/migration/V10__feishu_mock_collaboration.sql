CREATE TABLE feishu_connection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    connection_code VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    tenant_alias VARCHAR(128),
    mode VARCHAR(16) NOT NULL DEFAULT 'MOCK',
    status VARCHAR(24) NOT NULL DEFAULT 'NOT_ENABLED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_feishu_connection_company FOREIGN KEY (company_id) REFERENCES company(id),
    CONSTRAINT uk_feishu_connection_scope_code UNIQUE (company_id, connection_code)
);

CREATE TABLE feishu_destination (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    connection_id BIGINT NOT NULL,
    destination_type VARCHAR(24) NOT NULL,
    destination_key VARCHAR(128) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_feishu_destination_company FOREIGN KEY (company_id) REFERENCES company(id),
    CONSTRAINT fk_feishu_destination_connection FOREIGN KEY (connection_id) REFERENCES feishu_connection(id),
    CONSTRAINT uk_feishu_destination_scope_key UNIQUE (company_id, connection_id, destination_type, destination_key)
);

CREATE TABLE notification_policy (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    destination_id BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    template_version VARCHAR(32) NOT NULL DEFAULT 'v1',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_notification_policy_company FOREIGN KEY (company_id) REFERENCES company(id),
    CONSTRAINT fk_notification_policy_destination FOREIGN KEY (destination_id) REFERENCES feishu_destination(id),
    CONSTRAINT uk_notification_policy_scope_event_destination UNIQUE (company_id, event_type, destination_id)
);

CREATE TABLE notification_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    event_id VARCHAR(96) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    reference_no VARCHAR(96),
    request_id VARCHAR(64) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_notification_event_company FOREIGN KEY (company_id) REFERENCES company(id),
    CONSTRAINT uk_notification_event_scope_id UNIQUE (company_id, event_id)
);

CREATE TABLE notification_delivery (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    event_id BIGINT NOT NULL,
    destination_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    provider_message_id VARCHAR(128),
    last_error VARCHAR(500),
    sent_at TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_notification_delivery_company FOREIGN KEY (company_id) REFERENCES company(id),
    CONSTRAINT fk_notification_delivery_event FOREIGN KEY (event_id) REFERENCES notification_event(id),
    CONSTRAINT fk_notification_delivery_destination FOREIGN KEY (destination_id) REFERENCES feishu_destination(id),
    CONSTRAINT uk_notification_delivery_event_destination UNIQUE (event_id, destination_id)
);

CREATE TABLE notification_outbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    event_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    last_error VARCHAR(500),
    available_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_notification_outbox_company FOREIGN KEY (company_id) REFERENCES company(id),
    CONSTRAINT fk_notification_outbox_event FOREIGN KEY (event_id) REFERENCES notification_event(id),
    CONSTRAINT uk_notification_outbox_event UNIQUE (event_id)
);

CREATE INDEX idx_feishu_destination_scope ON feishu_destination(company_id, connection_id, enabled);
CREATE INDEX idx_notification_event_scope ON notification_event(company_id, event_type, created_at, id);
CREATE INDEX idx_notification_delivery_scope ON notification_delivery(company_id, status, updated_at, id);

INSERT INTO sys_permission (id, code, name, description) VALUES
    (30, 'feishu:view', '查看飞书协同', 'View Feishu collaboration metadata and delivery records'),
    (31, 'feishu:manage', '管理飞书配置', 'Manage Feishu connections, destinations and policies'),
    (32, 'feishu:notify', '发送飞书通知', 'Create controlled Feishu notification events'),
    (33, 'feishu:retry', '重试飞书通知', 'Retry failed Feishu notification deliveries');

INSERT INTO sys_role_permission (role_id, permission_id) VALUES
    (1, 30), (1, 31), (1, 32), (1, 33),
    (2, 30), (2, 32), (2, 33),
    (3, 30),
    (4, 30);
