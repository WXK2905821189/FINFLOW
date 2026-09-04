-- V15: retire the two demo CITIC bank accounts seeded by V1
--
-- V1 seeded `bank_account` id 1/2 as CITIC 中信银行基本户 / 一般户, carrying
-- simulated balances (986400.52 / 300000.00). They date from the v0.1 payments
-- era, when bank connectivity was modelled as "a bank service bean exists"
-- rather than as a proven, per-account connection.
--
-- Under the per-account direct-connect semantics (AccountDirectStatusService)
-- those rows are actively misleading. CITIC has no REAL adapter wired - the
-- CITIC line is scheduled for 9/18 - so the rows can only ever render as
-- NOT_CONNECTED, yet their simulated balances imply the opposite. This is
-- cleanup decision D1: remove the seed rows instead of leaving them disabled,
-- so no surface can quote a balance for a bank that was never connected.
--
-- Deletion order follows the foreign-key graph bottom-up. Every statement
-- filters with a sub-query against a *different* table, so the same script runs
-- unchanged on MySQL 8 and H2 (MySQL mode) - multi-table JOIN-DELETE syntax
-- differs between the two engines.
--
-- The final delete is guarded by bank_code + account_number on top of the id
-- list, so an account onboarded later under a recycled id can never be removed.

-- 1. Bank-data cache derived from these accounts. Re-derivable cache, not records.
DELETE FROM bank_data_statement WHERE bank_account_id IN (1, 2);
DELETE FROM bank_data_balance WHERE bank_account_id IN (1, 2);
DELETE FROM bank_data_sync_log
 WHERE task_id IN (SELECT id FROM bank_data_sync_task WHERE bank_account_id IN (1, 2));
DELETE FROM bank_data_raw_message
 WHERE task_id IN (SELECT id FROM bank_data_sync_task WHERE bank_account_id IN (1, 2));
DELETE FROM bank_data_sync_task WHERE bank_account_id IN (1, 2);

-- 2. Legacy transfer surface. /api/transfers was removed from the v0.4 scope and
--    V14 retired its permissions: payment_transfer retains no consumer, so rows
--    keyed to these accounts are unreachable history.
DELETE FROM payment_transfer_audit_event
 WHERE payment_id IN (SELECT id FROM payment_transfer WHERE payer_account_id IN (1, 2));
DELETE FROM payment_transfer WHERE payer_account_id IN (1, 2);

-- 3. Imported statement records bound to these accounts.
DELETE FROM statement_audit_event
 WHERE statement_id IN (SELECT id FROM statement_record WHERE bank_account_id IN (1, 2));
DELETE FROM statement_record WHERE bank_account_id IN (1, 2);

-- 4. The accounts themselves.
DELETE FROM bank_account
 WHERE id IN (1, 2)
   AND bank_code = 'CITIC'
   AND account_number IN ('6222000000004821', '6222000000007306');
