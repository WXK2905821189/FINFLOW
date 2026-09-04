-- V19: bank statement vendor currency code (CMB currencyNbr)
--
-- The statement row carried a canonical `currency` but not the code the bank actually sent.
-- For balances V17 stored it (vendor_currency_code) and for statements it was quietly
-- dropped: the adapter passed null for the entry currency, so the executor fell back to
-- the "CNY" default and the bank's own per-transaction currency code was lost.
--
-- That matters the moment a statement is compared against the bank's own export, whose
-- 币种 column is column 3 of 36 and would otherwise be blank on every row. It also matters
-- for a multi-currency account, where the canonical default is simply wrong.
--
-- Nullable: CITIC's DLTRNALL response carries no per-transaction currency, and rows captured
-- before this migration genuinely have no value for it. Nothing is backfilled or invented.
--
-- Cross-database note: one ALTER per column - H2 2.2.224 (MODE=MySQL) rejects the
-- comma-separated multi-column form that MySQL 8 accepts.

ALTER TABLE bank_data_statement ADD COLUMN vendor_currency_code VARCHAR(8) NULL COMMENT '币种 currencyNbr（银行侧代码，如 10=人民币）';
