-- V14: retire dead transfer permissions (module cleanup action A1)
--
-- The legacy /api/transfers endpoints and their workflow service were removed:
-- active payments/transfers are explicitly out of the v0.4 product scope (PRD)
-- and the frontend never referenced any of them. Their permissions therefore
-- have no remaining `@PreAuthorize` consumer:
--   id 2  `transaction:view`
--   id 6  `transfer:create`
--   id 7  `transfer:approve`
--   id 8  `transfer:execute`
--
-- `bank:view` / `bank:manage` stay: /api/bank-accounts still uses both, and
-- `data:query` stays: /api/data/{resource} capability status uses it.

DELETE FROM sys_role_permission
WHERE permission_id IN (
    SELECT id FROM sys_permission WHERE code IN ('transaction:view', 'transfer:create', 'transfer:approve', 'transfer:execute')
);

DELETE FROM sys_permission
WHERE code IN ('transaction:view', 'transfer:create', 'transfer:approve', 'transfer:execute');
