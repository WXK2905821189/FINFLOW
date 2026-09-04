-- V22: bank balance overdraft / interest fields (CMB NTQADINF ntqadinfz)
--
-- Completes the NTQADINF response coverage: the doc's field table lists four more
-- fields that FINFLOW did not yet store --
--
--   lmtovr  overdraft limit   part of the bank-side available-balance formula
--   intcod  interest code     S = 子公司虚拟余额 (head-office quota), must not be
--                             confused with real funds in group scenarios
--   intrat  annual rate       F(11,7)
--   mutdat  maturity date     8-digit yyyyMMdd (00000000 = none)
--
-- All columns are nullable and nothing is backfilled: adapters other than CMB may not
-- report these fields, and rows captured before this migration have no value for them.
-- intrat keeps the bank's scale (7 decimals) - do NOT apply the money scale(2) here.
--
-- Cross-database note: one ALTER per column (H2 2.2.224 rejects the comma form).

ALTER TABLE bank_data_balance ADD COLUMN overdraft_limit DECIMAL(19,2) NULL COMMENT '透支额度 lmtovr';
ALTER TABLE bank_data_balance ADD COLUMN interest_code VARCHAR(8) NULL COMMENT '利息码 intcod: S=子公司虚拟余额';
ALTER TABLE bank_data_balance ADD COLUMN interest_rate DECIMAL(11,7) NULL COMMENT '年利率 intrat';
ALTER TABLE bank_data_balance ADD COLUMN maturity_date VARCHAR(16) NULL COMMENT '到期日 mutdat yyyyMMdd';
