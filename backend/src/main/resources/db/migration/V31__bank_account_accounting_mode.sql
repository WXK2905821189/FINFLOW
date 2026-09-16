-- V31: 账户级制证模式（2026-09-16 决策：部分账户纯人工制证，数据留系统不进金蝶链路）。
-- KINGDEE_AUTO（默认）: 流水可走「AI 制证 → 推送金蝶 → 金蝶侧人工审核」链路；
-- MANUAL: 仅同步落库与查询，不出现 AI 制证推送入口（后端同样拒绝，双保险）。
ALTER TABLE bank_account ADD COLUMN accounting_mode VARCHAR(20) NOT NULL DEFAULT 'KINGDEE_AUTO';
