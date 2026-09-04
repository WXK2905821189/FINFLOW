-- V18: bank statement vendor fields (CMB trsQryByBreakPoint TRANSQUERYBYBREAKPOINT_Z2)
--
-- The statement table kept 7 of the 30 fields the bank returns. The 23 dropped ones are
-- not decoration - they are the fields a finance team needs to trust a row:
--
--   acctOnlineBal  balance after this transaction - lets you walk a day's movements and
--                  prove the sequence is complete without recomputing anything
--   valueDate      起息日 - the date interest actually accrues, which is NOT the trade date
--   reversalFlag   冲账标志 (* 冲账 / X 补账) - a reversal carries the opposite loan code,
--                  so a naive 收入/支出 sum double-counts it
--   infoFlag       tells you whether ctpAcctNbr is the payer or the payee; without it the
--                  counterparty column is ambiguous
--   loanCode       C/D - the bank's own 借贷码, kept next to the derived INCOME/EXPENSE
--   signedAmount   transAmount with its sign (D negative, C positive). The legacy `amount`
--                  column stays an unsigned magnitude because the executor's validation
--                  requires a positive accounting amount; this column is the bank's figure.
--
-- Every column is nullable: only the CMB adapter reports them, and rows captured before
-- this migration genuinely have no value for them. Nothing is backfilled or invented.
--
-- Cross-database note: one ALTER per column, not one ALTER with a comma-separated list.
-- Production MySQL 8 accepts both; H2 2.2.224 (MODE=MySQL, our test database) rejects the
-- comma form outright, so the portable shape is one statement per column.

ALTER TABLE bank_data_statement ADD COLUMN bank_account_no VARCHAR(35) NULL COMMENT '账号 acctNbr（银行侧口径）';
ALTER TABLE bank_data_statement ADD COLUMN value_date DATE NULL COMMENT '起息日 valueDate';
ALTER TABLE bank_data_statement ADD COLUMN loan_code VARCHAR(1) NULL COMMENT '借贷码 loanCode: C 贷方 / D 借方';
ALTER TABLE bank_data_statement ADD COLUMN signed_amount DECIMAL(20, 2) NULL COMMENT '带符号交易金额 transAmount：借方为负、贷方为正';
ALTER TABLE bank_data_statement ADD COLUMN text_code VARCHAR(12) NULL COMMENT '交易类型 textCode（附录A.9）';
ALTER TABLE bank_data_statement ADD COLUMN bill_number VARCHAR(32) NULL COMMENT '票据号 billNumber';
ALTER TABLE bank_data_statement ADD COLUMN remark_text_clt VARCHAR(200) NULL COMMENT '你方摘要 remarkTextClt';
ALTER TABLE bank_data_statement ADD COLUMN reversal_flag VARCHAR(1) NULL COMMENT '冲账标志 reversalFlag: * 冲账 / X 补账';
ALTER TABLE bank_data_statement ADD COLUMN acct_online_bal DECIMAL(20, 2) NULL COMMENT '每笔后余额 acctOnlineBal';
ALTER TABLE bank_data_statement ADD COLUMN extended_remark VARCHAR(32) NULL COMMENT '扩展摘要 extendedRemark';
ALTER TABLE bank_data_statement ADD COLUMN ctp_acct_nbr VARCHAR(64) NULL COMMENT '收付方帐号 ctpAcctNbr（完整，非本方账号故不脱敏）';
ALTER TABLE bank_data_statement ADD COLUMN ctp_bank_name VARCHAR(200) NULL COMMENT '收付方开户行行名 ctpBankName';
ALTER TABLE bank_data_statement ADD COLUMN ctp_bank_address VARCHAR(200) NULL COMMENT '收付方开户行地址 ctpBankAddress';
ALTER TABLE bank_data_statement ADD COLUMN fat_or_son_account VARCHAR(64) NULL COMMENT '母子公司帐号 fatOrSonAccount';
ALTER TABLE bank_data_statement ADD COLUMN fat_or_son_company_name VARCHAR(200) NULL COMMENT '母子公司名称 fatOrSonCompanyName';
ALTER TABLE bank_data_statement ADD COLUMN fat_or_son_bank_name VARCHAR(200) NULL COMMENT '母子公司开户行行名 fatOrSonBankName';
ALTER TABLE bank_data_statement ADD COLUMN fat_or_son_bank_address VARCHAR(200) NULL COMMENT '母子公司开户行地址 fatOrSonBankAddress';
ALTER TABLE bank_data_statement ADD COLUMN info_flag VARCHAR(1) NULL COMMENT '信息标志 infoFlag: 空 付方/子公司, 1 收方/子公司, 2 收方/母公司, 3 原收方/子公司';
ALTER TABLE bank_data_statement ADD COLUMN business_name VARCHAR(64) NULL COMMENT '业务名称 businessName';
ALTER TABLE bank_data_statement ADD COLUMN business_text VARCHAR(400) NULL COMMENT '网银业务摘要 businessText';
ALTER TABLE bank_data_statement ADD COLUMN request_nbr VARCHAR(16) NULL COMMENT '网银流程实例号 requestNbr';
ALTER TABLE bank_data_statement ADD COLUMN yur_ref VARCHAR(32) NULL COMMENT '网银业务参考号 yurRef';
ALTER TABLE bank_data_statement ADD COLUMN virtual_nbr VARCHAR(16) NULL COMMENT '虚拟户编号 virtualNbr';
ALTER TABLE bank_data_statement ADD COLUMN mch_order_nbr VARCHAR(64) NULL COMMENT '商务支付订单号 mchOrderNbr';
ALTER TABLE bank_data_statement ADD COLUMN trans_card_nbr VARCHAR(64) NULL COMMENT '记账卡号 transCardNbr';
ALTER TABLE bank_data_statement ADD COLUMN reserve VARCHAR(64) NULL COMMENT '保留字 reserve';
