-- V37（W4 规则中心，2026-09-18）：规则分组表 + 规则挂组列 + seed 规则入默认分组。
-- 背景：大类规则页原为只读视图（维护走迁移），W4 起开放 CRUD + Excel 导入 + 分组管理。

CREATE TABLE kingdee_rule_group (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    description VARCHAR(255) NULL,
    sort_no INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NULL DEFAULT NULL,
    updated_at TIMESTAMP NULL DEFAULT NULL,
    CONSTRAINT uk_kingdee_rule_group_name UNIQUE (name)
);

ALTER TABLE kingdee_voucher_rule ADD COLUMN group_id BIGINT NULL;

-- 22 条 seed 规则归入默认分组（子查询跨表，MySQL/H2 均允许）
INSERT INTO kingdee_rule_group (name, description, sort_no, created_at, updated_at)
VALUES ('财务默认规则', 'V34 seed 导入的财务映射规则', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

UPDATE kingdee_voucher_rule
SET group_id = (SELECT id FROM kingdee_rule_group WHERE name = '财务默认规则')
WHERE group_id IS NULL;
