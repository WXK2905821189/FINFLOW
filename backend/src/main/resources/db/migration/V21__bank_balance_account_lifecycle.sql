-- V21: bank balance account lifecycle fields (CMB NTQADINF ntqadinfz)
--
-- NTQADINF marks four more fields as always-returned (Y) in the CMB CloudDC doc:
--
--   stscod  account status     A = active, B = frozen, C = closed
--   opndat  open date          8-digit yyyyMMdd as the bank codes it
--   inttyp  interest type      ZZZ = non-interest-bearing, TD2 = 3-month fixed, ...
--   dpstxt  deposit term       e.g. Z(12)
--
-- stscod matters most: a frozen or closed account with a stale balance figure is a
-- reconciliation hazard, and without the status column nothing in the data says so.
-- The other three complete the account identity picture (when it was opened, how it
-- earns interest, for how long).
--
-- All columns are nullable and nothing is backfilled: adapters other than CMB may not
-- report these fields, and rows captured before this migration have no value for them.
--
-- Cross-database note: one ALTER per column (H2 2.2.224 rejects the comma form).

ALTER TABLE bank_data_balance ADD COLUMN account_status VARCHAR(8) NULL COMMENT '账户状态 stscod: A=活动 B=冻结 C=关户';
ALTER TABLE bank_data_balance ADD COLUMN open_date VARCHAR(16) NULL COMMENT '开户日 opndat yyyyMMdd';
ALTER TABLE bank_data_balance ADD COLUMN interest_type VARCHAR(16) NULL COMMENT '利率类型 inttyp: ZZZ=不计息等';
ALTER TABLE bank_data_balance ADD COLUMN deposit_term VARCHAR(16) NULL COMMENT '存期 dpstxt';
