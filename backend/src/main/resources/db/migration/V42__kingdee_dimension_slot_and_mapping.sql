-- V42（2026-09-21）：金蝶核算维度「槽位配置 + 值映射」两张表。
--
-- 背景（图虫侧 19 条规则落地评估，docs/kingdee-openapi/toochong-rules-assessment-20260921.md）：
--   该批规则要求一条分录同时带多个核算维度（如「供应商 + 部门 + 业务线」），且维度值必须是
--   金蝶侧的**档案编码**（VEN00511 这类），而不是 FINFLOW 侧的对手方名称。现有引擎两处不足：
--     ① 分录草稿只有一个 dimension/dimensionValue 槽位，无法多头注入；
--     ② SUPPLIER/CUSTOMER/EMPLOYEE 维度直接取对手方名称，语义不符（金蝶要编码）。
--   用户 2026-09-21 拍板：**先配齐能力，规则暂不入库**；维度映射新建独立表，
--   **必须能在系统界面直接改**（不靠改代码/改配置发版）。
--
-- 表 1：kingdee_dimension_slot —— 维度类型 → 账套弹性域槽位（账套级配置，一条维度类型一行）
--   槽位（FF100002 这类键）是**账套级**配置，只能报错驱动试出（银行账号 = FF100002 已实测，
--   见 docs/kingdee-openapi/gl-voucher-calibration-20260921.md）；未试出前 slot 留空，
--   推送时按「无槽位」跳过并标注，不猜。
--   dimension_kind 记录维度类型：BASE_DATA（基础资料，如银行账号/供应商/员工）、
--   ASSIST（辅助资料）、CUSTOM（自定义维度，账套里以 ZDYxxxx 编码）。
--
-- 表 2：kingdee_dimension_mapping —— 维度值映射（来源值 → 金蝶档案编码）
--   source_key 是 FINFLOW 侧可见值（对手方名称 / 员工姓名 / 业务线关键词 / 账户号）；
--   source_kind 决定比较方式：NAME=精确、KEYWORD=包含、ACCOUNT=账号精确、CODE=编码精确。
--   org_code='' 表示通用（不限组织）；非空表示仅该金蝶组织生效——同一供应商在不同主体
--   可能对应不同档案，用组织限定消歧（与银行账号映射同思路）。
--   唯一键含 org_code 且 org_code NOT NULL DEFAULT ''：MySQL 唯一索引不比较 NULL，
--   若允许 NULL 则同一 (类型,来源值) 可重复插入两条「通用」映射，故用空串代替 NULL。
--
-- 空值语义：某维度类型在 slot 表无行 / slot 为空 / 值映射查不到 → 该维度不注入，
--   并在推送结果里标注「维度 X 未映射」，便于人工补（不静默按空维度记账）。

CREATE TABLE kingdee_dimension_slot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dimension_type VARCHAR(32) NOT NULL,
    dimension_name VARCHAR(128) NULL,
    dimension_code VARCHAR(64) NULL,
    slot VARCHAR(32) NULL,
    dimension_kind VARCHAR(32) NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    remark VARCHAR(500) NULL,
    created_at TIMESTAMP NULL DEFAULT NULL,
    updated_at TIMESTAMP NULL DEFAULT NULL,
    CONSTRAINT uk_kd_dim_slot_type UNIQUE (dimension_type)
);

CREATE TABLE kingdee_dimension_mapping (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dimension_type VARCHAR(32) NOT NULL,
    source_key VARCHAR(128) NOT NULL,
    source_kind VARCHAR(32) NOT NULL DEFAULT 'NAME',
    kingdee_value VARCHAR(128) NOT NULL,
    kingdee_name VARCHAR(256) NULL,
    org_code VARCHAR(32) NOT NULL DEFAULT '',
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    remark VARCHAR(500) NULL,
    created_at TIMESTAMP NULL DEFAULT NULL,
    updated_at TIMESTAMP NULL DEFAULT NULL,
    CONSTRAINT uk_kd_dim_map_key UNIQUE (dimension_type, source_key, org_code)
);

CREATE INDEX idx_kd_dim_map_type ON kingdee_dimension_mapping (dimension_type, enabled);

-- ---- seed：槽位映射（2026-09-21 真实账套取证） ----
-- 取证方式：GL_VOUCHER 元数据（QueryBusinessInfo）里每个 FDETAILID__FFxxxx 字段自带
-- 「Name = 维度名」，与 BD_FLEXITEMPROPERTY 的维度编码一一对应；且已用真实凭证实证：
--   · 银行账号 FF100002 —— 凭证 16043 保存成功（早前校准）
--   · 供应商 FFLEX4   —— 凭证 16059 保存成功（基线报错点名「供应商」→ 注入后通过）
--   · 供应商/客户/员工 FFLEX4/FFLEX6/FFLEX7 —— 凭证 16060 三槽位同凭证保存成功
-- 上述实证凭证均已即时删除并回查为空。台账见
-- docs/kingdee-openapi/kingdee-dimension-slot-ledger-20260921.md。

-- 自定义维度（ZDYxxxx）：元数据实测 FF100002=银行账号（ZDY0001，已实证）。
INSERT INTO kingdee_dimension_slot (dimension_type, dimension_name, dimension_code, slot, dimension_kind, enabled, remark, created_at, updated_at) VALUES ('BANK_ACCOUNT', '银行账号', 'ZDY0001', 'FF100002', 'CUSTOM', 1, '实证：凭证 16043 保存成功；值取 CN_BANKACNT 档案编码（账户级映射 V41）', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO kingdee_dimension_slot (dimension_type, dimension_name, dimension_code, slot, dimension_kind, enabled, remark, created_at, updated_at) VALUES ('PROJECT', '项目', 'ZDY0002', 'FF100003', 'CUSTOM', 1, '槽位由元数据给出（FDETAILID__FF100003 Name=项目），未单独实证', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO kingdee_dimension_slot (dimension_type, dimension_name, dimension_code, slot, dimension_kind, enabled, remark, created_at, updated_at) VALUES ('INVESTOR', '投资人', 'ZDY0003', 'FF100004', 'CUSTOM', 1, '槽位由元数据给出（FDETAILID__FF100004 Name=投资人），未单独实证', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
-- 合同号（ZDY0004）：元数据里未出现对应槽位（该账套可能未在凭证模板启用），留空待试。
INSERT INTO kingdee_dimension_slot (dimension_type, dimension_name, dimension_code, slot, dimension_kind, enabled, remark, created_at, updated_at) VALUES ('CONTRACT', '合同号', 'ZDY0004', NULL, 'CUSTOM', 1, 'meta 未列出槽位：需确认该账套是否在凭证模板启用了合同号维度', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 标准维度（HSWDxx_SYS）：槽位 FFLEX4~FFLEX13 按元数据顺序一一对应。
INSERT INTO kingdee_dimension_slot (dimension_type, dimension_name, dimension_code, slot, dimension_kind, enabled, remark, created_at, updated_at) VALUES ('SUPPLIER', '供应商', 'HSWD01_SYS', 'FFLEX4', 'BASE_DATA', 1, '实证：凭证 16059/16060 保存成功；值取 BD_Supplier 档案编码（如 VEN00001）', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO kingdee_dimension_slot (dimension_type, dimension_name, dimension_code, slot, dimension_kind, enabled, remark, created_at, updated_at) VALUES ('DEPARTMENT', '部门', 'HSWD03_SYS', 'FFLEX5', 'BASE_DATA', 1, '槽位由元数据给出（Name=部门），未单独实证', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO kingdee_dimension_slot (dimension_type, dimension_name, dimension_code, slot, dimension_kind, enabled, remark, created_at, updated_at) VALUES ('CUSTOMER', '客户', 'HSWD02_SYS', 'FFLEX6', 'BASE_DATA', 1, '实证：凭证 16060 保存成功；值取 BD_Customer 档案编码', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO kingdee_dimension_slot (dimension_type, dimension_name, dimension_code, slot, dimension_kind, enabled, remark, created_at, updated_at) VALUES ('EMPLOYEE', '员工', 'HSWD04_SYS', 'FFLEX7', 'BASE_DATA', 1, '实证：凭证 16060 保存成功；值取 BD_Empinfo 档案编码（如 001）', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO kingdee_dimension_slot (dimension_type, dimension_name, dimension_code, slot, dimension_kind, enabled, remark, created_at, updated_at) VALUES ('MATERIAL', '物料', 'HSWD05_SYS', 'FFLEX8', 'BASE_DATA', 1, '槽位由元数据给出，未单独实证', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO kingdee_dimension_slot (dimension_type, dimension_name, dimension_code, slot, dimension_kind, enabled, remark, created_at, updated_at) VALUES ('EXPENSE_ITEM', '费用项目', 'HSWD07_SYS', 'FFLEX9', 'BASE_DATA', 1, '槽位由元数据给出，未单独实证', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO kingdee_dimension_slot (dimension_type, dimension_name, dimension_code, slot, dimension_kind, enabled, remark, created_at, updated_at) VALUES ('ASSET_TYPE', '资产类别', 'HSWD06_SYS', 'FFLEX10', 'BASE_DATA', 1, '槽位由元数据给出，未单独实证', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO kingdee_dimension_slot (dimension_type, dimension_name, dimension_code, slot, dimension_kind, enabled, remark, created_at, updated_at) VALUES ('ORG', '组织机构', 'HSWD08_SYS', 'FFLEX11', 'BASE_DATA', 1, '槽位由元数据给出，未单独实证；规则引擎已有 ORG 维度（内部往来）', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO kingdee_dimension_slot (dimension_type, dimension_name, dimension_code, slot, dimension_kind, enabled, remark, created_at, updated_at) VALUES ('MATERIAL_GROUP', '物料分组', 'HSWD09_SYS', 'FFLEX12', 'BASE_DATA', 1, '槽位由元数据给出，未单独实证', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO kingdee_dimension_slot (dimension_type, dimension_name, dimension_code, slot, dimension_kind, enabled, remark, created_at, updated_at) VALUES ('CUSTOMER_GROUP', '客户分组', 'HSWD10_SYS', 'FFLEX13', 'BASE_DATA', 1, '槽位由元数据给出，未单独实证', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 业务线（用户 2026-09-21 拍板：**用「项目 ZDY0002」承载**）。
-- 账套 BD_FLEXITEMPROPERTY 里不存在「业务线」维度，图虫侧规则要求它 →
-- 与 PROJECT 共用槽位 FF100003，值为项目档案编码；规则模板里 dimension type 写 BUSINESS_LINE 时
-- 解析到的槽位/值语义与 PROJECT 一致。
INSERT INTO kingdee_dimension_slot (dimension_type, dimension_name, dimension_code, slot, dimension_kind, enabled, remark, created_at, updated_at) VALUES ('BUSINESS_LINE', '业务线（以项目维度承载）', 'ZDY0002', 'FF100003', 'CUSTOM', 1, '用户 2026-09-21 拍板：业务线用项目 ZDY0002 承载，与 PROJECT 共用槽位；值为项目档案编码', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
