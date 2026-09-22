-- V43（2026-09-22，W16-A1）：一键推送至金蝶异步任务（规则编排）
--
-- 背景（用户 W16 拍板）：
--   AI 制证全链路退役。流水查询页改为单一「一键推送至金蝶」按钮：勾选流水 → 后台任务
--   跑规则中心匹配 → **仅唯一命中（AUTO_FILL）且无需人工金额**的行自动组装推送金蝶草稿；
--   多候选（CANDIDATES）/未命中（UNMATCHED）/需人工金额/不可制证（NOT_ELIGIBLE）/
--   推送失败 → 全部落为「问题凭证」（凭证中心可查，A2 编辑器处理）。
--
-- 语义：
--   status      RUNNING / COMPLETED / FAILED（线程异常时整批失败，message 记原因）
--   计数四列    pushed（自动推送成功）/ problem（问题凭证）/ skipped（跳过）
--               + already（幂等：此前已推送成功，终局不动）
--   rows_json   逐行结果 JSON（PushRowResult 数组，含 outcome 与失败原因），
--               按 PushJobService 的上限截断，避免超长批次写失败
--   created_by  提交人（审计可追）
--
-- 同批退役：DROP TABLE ai_voucher_job（V40 异步任务的唯一承载表；AI 制证链路已删，
-- 表内历史任务数据随制证能力一并退役——AI 草稿与推送结果本身仍留 statement_record 可查）。
-- Flyway 历史不可改：V40 文件保留在迁移链中，本迁移负责删表。

CREATE TABLE bank_push_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    batch_no VARCHAR(64) NULL,
    total_count INT NOT NULL DEFAULT 0,
    pushed_count INT NOT NULL DEFAULT 0,
    problem_count INT NOT NULL DEFAULT 0,
    skipped_count INT NOT NULL DEFAULT 0,
    already_count INT NOT NULL DEFAULT 0,
    rows_json TEXT NULL,
    message TEXT NULL,
    created_by BIGINT NULL,
    created_at TIMESTAMP NULL DEFAULT NULL,
    finished_at TIMESTAMP NULL DEFAULT NULL
);

CREATE INDEX idx_bank_push_job_company_created ON bank_push_job (company_id, created_at);

DROP TABLE IF EXISTS ai_voucher_job;
