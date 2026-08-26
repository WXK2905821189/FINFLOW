CREATE TABLE sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    email VARCHAR(128) NOT NULL UNIQUE,
    phone VARCHAR(32),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sys_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(64) NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sys_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(96) NOT NULL UNIQUE,
    name VARCHAR(96) NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sys_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES sys_role(id)
);

CREATE TABLE sys_role_permission (
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES sys_role(id),
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES sys_permission(id)
);

CREATE TABLE bank_account (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    bank_code VARCHAR(32) NOT NULL,
    account_name VARCHAR(128) NOT NULL,
    account_number VARCHAR(128) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'CNY',
    available_balance DECIMAL(19, 2) NOT NULL DEFAULT 0.00,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sys_user_status ON sys_user(status);
CREATE INDEX idx_bank_account_code_status ON bank_account(bank_code, status);

INSERT INTO sys_role (id, code, name, description) VALUES
    (1, 'ADMIN', '系统管理员', 'Manages users, roles, accounts and payments'),
    (2, 'FINANCE_STAFF', '财务专员', 'Creates and executes approved transfers'),
    (3, 'FINANCE_MANAGER', '财务经理', 'Reviews finance data and approves transfers'),
    (4, 'VIEWER', '业务查看者', 'Read-only finance access');

INSERT INTO sys_permission (id, code, name, description) VALUES
    (1, 'dashboard:view', '查看仪表盘', 'View dashboard'),
    (2, 'transaction:view', '查看交易记录', 'View transactions'),
    (3, 'user:manage', '管理用户', 'Manage users'),
    (4, 'bank:view', '查看银行账户', 'View bank accounts'),
    (5, 'bank:manage', '管理银行账户', 'Manage bank accounts'),
    (6, 'transfer:create', '发起转账', 'Create transfers'),
    (7, 'transfer:approve', '审批转账', 'Approve transfers'),
    (8, 'transfer:execute', '执行转账', 'Execute transfers'),
    (9, 'role:manage', '管理角色权限', 'Manage roles and permissions');

INSERT INTO sys_role_permission (role_id, permission_id) VALUES
    (1, 1), (1, 2), (1, 3), (1, 4), (1, 5), (1, 6), (1, 8), (1, 9),
    (2, 1), (2, 2), (2, 4), (2, 6), (2, 8),
    (3, 1), (3, 2), (3, 4), (3, 7),
    (4, 1), (4, 2), (4, 4);

INSERT INTO bank_account (id, bank_code, account_name, account_number, currency, available_balance, status) VALUES
    (1, 'CITIC', '中信银行基本户', '6222000000004821', 'CNY', 986400.52, 'ACTIVE'),
    (2, 'CITIC', '中信银行一般户', '6222000000007306', 'CNY', 300000.00, 'ACTIVE');
