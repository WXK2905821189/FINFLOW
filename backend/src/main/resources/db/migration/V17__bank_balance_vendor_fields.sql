-- V17: bank balance vendor fields (CMB NTQADINF ntqadinfz)
--
-- The balance table stored a single number, `available_balance`. That was never what
-- the bank sends: NTQADINF reports four distinct amounts per account, and they answer
-- different questions.
--
--   accblv  previous day balance = online balance - today's financial transactions
--   onlblv  online balance       = the account's actual funds
--   hldblv  frozen balance       = judicial + bank holds combined
--   avlblv  available balance    = what can actually be spent     (already stored)
--
-- Collapsing those into one figure hid the two things a finance team asks about most:
-- how much moved today, and how much of the balance is locked. The account identity
-- fields (ccynbr / bbknbr / accnbr / accnam / accitm / relnbr) were parsed and
-- discarded for the same reason - they matter when a balance has to be reconciled
-- against the bank's own statement.
--
-- All columns are nullable: adapters other than CMB may not report them, and rows
-- captured before this migration genuinely have no value for them. No value is
-- backfilled or invented - those rows simply predate the extension.
--
-- Cross-database note: one ALTER per column, not one ALTER with a comma-separated list.
-- Production MySQL 8 accepts both; H2 2.2.224 (MODE=MySQL, our test database) rejects the
-- comma form outright, so the portable shape is one statement per column.

ALTER TABLE bank_data_balance ADD COLUMN online_balance DECIMAL(19, 2) NULL;
ALTER TABLE bank_data_balance ADD COLUMN frozen_balance DECIMAL(19, 2) NULL;
ALTER TABLE bank_data_balance ADD COLUMN previous_day_balance DECIMAL(19, 2) NULL;
ALTER TABLE bank_data_balance ADD COLUMN vendor_currency_code VARCHAR(8) NULL;
ALTER TABLE bank_data_balance ADD COLUMN branch_code VARCHAR(8) NULL;
ALTER TABLE bank_data_balance ADD COLUMN bank_account_no VARCHAR(64) NULL;
ALTER TABLE bank_data_balance ADD COLUMN bank_account_name VARCHAR(128) NULL;
ALTER TABLE bank_data_balance ADD COLUMN account_item VARCHAR(16) NULL;
ALTER TABLE bank_data_balance ADD COLUMN customer_relation_no VARCHAR(32) NULL;
