-- V20: the bank's own debit/credit totals per sync task (CMB trsQryByBreakPoint Z1)
--
-- Every page of a statement query carries a Z1 record with the bank's own aggregates for
-- that page: debitNums / debitAmount / creditNums / creditAmount. Until now those were
-- parsed and thrown away, which silently discarded the one reconciliation figure that does
-- not depend on FINFLOW's own dedup and validation: the bank saying "this window contains
-- N debits totalling X and N credits totalling Y".
--
-- The executor sums the per-page figures across pages and windows onto the task row, so
-- the stored value is a window-level total the bank itself attests to. Amounts keep the
-- bank's sign (debit negative, credit positive) exactly as the bank reports them.
--
-- Nullable: CITIC reports no equivalent aggregates, rows captured before this migration
-- have no value, and a task that failed before reaching a page legitimately has none.
-- Nothing is backfilled or invented.
--
-- Cross-database note: one ALTER per column - H2 2.2.224 (MODE=MySQL) rejects the
-- comma-separated multi-column form that MySQL 8 accepts.

ALTER TABLE bank_data_sync_task ADD COLUMN debit_amount DECIMAL(19, 2) NULL COMMENT '借方合计 debitAmount（银行 Z1 口径，带符号）';
ALTER TABLE bank_data_sync_task ADD COLUMN debit_nums INT NULL COMMENT '借方笔数 debitNums（银行 Z1 口径）';
ALTER TABLE bank_data_sync_task ADD COLUMN credit_amount DECIMAL(19, 2) NULL COMMENT '贷方合计 creditAmount（银行 Z1 口径，带符号）';
ALTER TABLE bank_data_sync_task ADD COLUMN credit_nums INT NULL COMMENT '贷方笔数 creditNums（银行 Z1 口径）';
