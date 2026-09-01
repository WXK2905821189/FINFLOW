-- V13: permission code convergence (architecture review action 3)
--
-- Converges the three historical bank-sync trigger aliases onto the canonical
-- code `bankdata:sync:trigger` (id 29, introduced in V9 with the controlled
-- STATEMENT_PULL trigger flow):
--   id 21 `bankdata:sync`      (V4, legacy alias)
--   id 23 `bank-sync:trigger`  (V4, legacy alias)
-- and retires id 27 `bankdata:payment:view`, which no endpoint references
-- since payment projection queries were removed from the v0.4 scope.
--
-- Roles that held a retired alias keep the ability to trigger synchronizations:
-- permission 29 is backfilled first so no role loses access.

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT DISTINCT rp.role_id, 29
FROM sys_role_permission rp
JOIN sys_permission p ON p.id = rp.permission_id
WHERE p.code IN ('bankdata:sync', 'bank-sync:trigger')
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission existing
      WHERE existing.role_id = rp.role_id
        AND existing.permission_id = 29
  );

DELETE FROM sys_role_permission
WHERE permission_id IN (
    SELECT id FROM sys_permission WHERE code IN ('bankdata:sync', 'bank-sync:trigger', 'bankdata:payment:view')
);

DELETE FROM sys_permission
WHERE code IN ('bankdata:sync', 'bank-sync:trigger', 'bankdata:payment:view');
