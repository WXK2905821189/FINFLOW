CREATE TABLE connection_profile (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    connection_code VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(128) NOT NULL,
    provider_type VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(24) NOT NULL DEFAULT 'NOT_ENABLED',
    last_checked_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE connection_operation_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_no VARCHAR(64) NOT NULL UNIQUE,
    task_type VARCHAR(64) NOT NULL,
    connection_id BIGINT,
    status VARCHAR(24) NOT NULL,
    request_id VARCHAR(64),
    summary VARCHAR(500),
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_connection_task_profile FOREIGN KEY (connection_id) REFERENCES connection_profile(id)
);

CREATE TABLE connection_operation_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT,
    level VARCHAR(16) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    result VARCHAR(24) NOT NULL,
    request_id VARCHAR(64),
    message VARCHAR(500),
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_connection_log_task FOREIGN KEY (task_id) REFERENCES connection_operation_task(id)
);

CREATE INDEX idx_connection_profile_status ON connection_profile(enabled, status, connection_code);
CREATE INDEX idx_connection_task_query ON connection_operation_task(status, task_type, created_at, id);
CREATE INDEX idx_connection_task_request ON connection_operation_task(request_id, created_at);
CREATE INDEX idx_connection_log_query ON connection_operation_log(result, level, occurred_at, id);
CREATE INDEX idx_connection_log_request ON connection_operation_log(request_id, occurred_at);

INSERT INTO sys_permission (id, code, name, description) VALUES
    (15, 'connection:view', '查看连接状态', 'View connection metadata and status'),
    (16, 'connection:manage', '管理连接元数据', 'Manage connection metadata without secrets'),
    (17, 'operation:monitor', '查看运营监控', 'View operation tasks and connection monitoring'),
    (18, 'operation:log:view', '查看运营日志', 'View sanitized operation logs'),
    (19, 'data:query', '查看数据查询能力', 'View server-side data query capability status');

INSERT INTO sys_role_permission (role_id, permission_id) VALUES
    (1, 15), (1, 16), (1, 17), (1, 18), (1, 19),
    (3, 15), (3, 17), (3, 18), (3, 19),
    (2, 19),
    (4, 19);
