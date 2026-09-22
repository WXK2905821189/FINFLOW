-- V44（2026-09-23，W16-A2）：问题凭证编辑器 —— 落桶标记 + 编辑态存储
--
-- 背景（规划 docs/voucher-rule-long-task-plan-20260922.md 阶段 2）：
--   一键推送落下的 PROBLEM_* 行结果此前只存在 bank_push_job.rows_json（批任务产物），
--   statement_record 行级无标记 ⇒ 凭证中心「问题凭证」桶无法推导，编辑器无落点。
--   本迁移给 statement_record 增加五个列：
--
-- 语义：
--   problem_type        落桶原因（PROBLEM_CANDIDATES / PROBLEM_UNMATCHED /
--                       PROBLEM_MANUAL_AMOUNT / PROBLEM_ELIGIBLE / PROBLEM_PUSH_FAILED）。
--                       非空 = 在问题桶；NULL = 不在。推送成功（GL_PUSHED）时清空 ⇒ 自动出列。
--   problem_reason      落桶/失败原因（人话，编辑器列表与详情直接展示）
--   problem_edit_json   A2 编辑器的人工编辑态（分录数组 JSON，结构见 VoucherProblemEditDoc）：
--                       PUT 保存重校验后的借/贷分录 + 摘要；submit 以此为准组装报文
--   problem_updated_by  最近编辑/落桶操作人
--   problem_updated_at  最近编辑/落桶时间（问题桶默认排序键）
--
-- 纪律：一条 ALTER 一列（RDS 可空列 + 显式 NULL 默认，见 MEMORY §后端硬规范）。

ALTER TABLE statement_record ADD COLUMN problem_type VARCHAR(32) NULL;
ALTER TABLE statement_record ADD COLUMN problem_reason VARCHAR(500) NULL;
ALTER TABLE statement_record ADD COLUMN problem_edit_json TEXT NULL;
ALTER TABLE statement_record ADD COLUMN problem_updated_by BIGINT NULL;
ALTER TABLE statement_record ADD COLUMN problem_updated_at TIMESTAMP NULL DEFAULT NULL;

CREATE INDEX idx_statement_problem_type ON statement_record (problem_type);
