-- V26: 字典中心（系统管理 → 字典中心）
--
-- 目标：让管理员在页面上维护"字段值清单"（如公司主体及其属性），改字段不再改代码。
-- 结构：两层 —— sys_dict_type（字典类型，如 company_entity 公司主体）
--              → sys_dict_item（字典项，code/label 唯一定位 + extra_json 扩展属性）。
-- extra_json 承载类型自定义的任意键值（税号/开户行/银行账号…），前端按 JSON 编辑，
-- 消费方按需取键，新增字段零迁移。
--
-- 权限：system:dict:manage（id=41）管理端点专用；消费方读取端点仅需登录态。

CREATE TABLE sys_dict_type (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    type_code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(255) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_by BIGINT NULL,
    created_at TIMESTAMP NULL DEFAULT NULL,
    updated_at TIMESTAMP NULL DEFAULT NULL,
    CONSTRAINT uk_sys_dict_type_code UNIQUE (type_code)
);

CREATE TABLE sys_dict_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    type_id BIGINT NOT NULL,
    item_code VARCHAR(64) NOT NULL,
    label VARCHAR(128) NOT NULL,
    extra_json TEXT NULL,
    sort_no INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    remark VARCHAR(255) NULL,
    created_by BIGINT NULL,
    created_at TIMESTAMP NULL DEFAULT NULL,
    updated_at TIMESTAMP NULL DEFAULT NULL,
    CONSTRAINT uk_sys_dict_item UNIQUE (type_id, item_code)
);

INSERT INTO sys_permission (id, code, name, description) VALUES
    (41, 'system:dict:manage', '管理字典中心', 'Manage system dictionaries (types, items and their extension fields)');

INSERT INTO sys_role_permission (role_id, permission_id) VALUES
    (1, 41),
    (2, 41);

-- 种子：公司主体（示例一条，extra 字段留空由管理员在页面上补）
INSERT INTO sys_dict_type (type_code, name, description, status, created_by, created_at, updated_at)
VALUES ('company_entity', '公司主体', '集团内公司主体清单及其属性（税号、开户行等），供各模块下拉与取值', 'ACTIVE', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO sys_dict_item (type_id, item_code, label, extra_json, sort_no, status, remark, created_by, created_at, updated_at)
VALUES ((SELECT id FROM sys_dict_type WHERE type_code = 'company_entity'),
        'xyrc', '北京雪云锐创科技有限公司', NULL, 0, 'ACTIVE', '示例条目，属性请在字典中心维护', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
