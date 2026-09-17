-- V32: bank account soft delete + scope ai:config to super admin.
--
-- 1) bank_account.deleted: soft delete marker (2026-09-17 user feedback: the archive
--    board lacks an "remove account" action). Physical DELETE is blocked by NOT NULL
--    foreign keys from bank_data_statement / bank_data_balance / bank_data_sync_log /
--    payment, and financial raw data must be retained anyway (原数据存储 requirement).
--    Deleted accounts disappear from the archive board, account dropdowns, data queries
--    and the nightly scheduler; historical statements/balances stay queryable.
-- 2) ai:config (permission 43) was granted to ADMIN(1) + FINANCE_STAFF(2) in V27. The
--    AI settings/status pages must be super-admin only (2026-09-17 user feedback): the
--    admin configures the gateway once, every member keeps using AI capabilities via
--    ai:use (42, untouched for roles 1/2/3).

ALTER TABLE bank_account ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0;

DELETE FROM sys_role_permission WHERE permission_id = 43 AND role_id = 2;
