-- V40（2026-09-21）：一键 AI 制证异步任务（DRAFT 模式后台化）
--
-- 背景（用户 2026-09-21 反馈）：
--   点「AI 制证为草稿」是同步请求——服务端逐条跑「转入 → AI 建议 → 落草稿」，
--   批量一多就长时间占住页面（弹窗 + loading）。用户要求：DRAFT 走后台任务，
--   页面立即返回，进度与结果统一在「凭证中心」看，**且失败必须说明原因**。
--
-- 语义：
--   mode        DRAFT（生成草稿，本表承载）/ PUSH（同步推送，仍走原链路，不落本表）
--   status      RUNNING / COMPLETED / FAILED（线程异常时整批失败，message 记原因）
--   计数五列    与 AiVoucherBatchResponse 同口径（草稿/推送/幂等跳过/跳过/失败），
--               任务完成时回填，前端轮询本行即可画进度与结果
--   rows_json   逐行结果（AiVoucherRowResult 数组的 JSON）。存 TEXT 上限约 64KB，
--               服务端写入时按 ROWS_JSON_MAX_CHARS 截断并追加一条说明行，避免超长批次写失败。
--   created_by  提交人（谁点的一键制证，审计可追）
--
-- 查询口径：按 (company_id, created_at DESC) 取「最近一个任务」给前端轮询；
-- 公司域隔离与其余银行数据模块一致（companyScope）。

CREATE TABLE ai_voucher_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    mode VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    batch_no VARCHAR(64) NULL,
    total_count INT NOT NULL DEFAULT 0,
    draft_count INT NOT NULL DEFAULT 0,
    pushed_count INT NOT NULL DEFAULT 0,
    already_count INT NOT NULL DEFAULT 0,
    skipped_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    rows_json TEXT NULL,
    message TEXT NULL,
    created_by BIGINT NULL,
    created_at TIMESTAMP NULL DEFAULT NULL,
    finished_at TIMESTAMP NULL DEFAULT NULL
);

CREATE INDEX idx_ai_voucher_job_company_created ON ai_voucher_job (company_id, created_at);
