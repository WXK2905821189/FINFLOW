-- V39：凭证撤回（W10）
-- 语义：未成功推送金蝶（push_status 不属于 PUSHED / GL_PUSHED）的凭证可撤回。
-- 撤回 = 将 review_status 置为 WITHDRAWN（保留记录与凭证号用于追溯 + 审计），
--        并使该流水回到「可重新制证」状态（银行数据层不再把它判为已转入）。
-- 注：一条 ALTER 一列（Flyway 历史不可改，增量迁移保持单列语句便于排错）。
ALTER TABLE statement_record ADD COLUMN withdrawn_at TIMESTAMP NULL DEFAULT NULL;
ALTER TABLE statement_record ADD COLUMN withdrawn_by BIGINT NULL;
