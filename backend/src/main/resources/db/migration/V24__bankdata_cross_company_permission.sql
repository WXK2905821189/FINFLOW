-- V24: cross-company bank data view permission
--
-- Background (2026-09-07 product feedback): the group runs many subsidiaries whose bank
-- accounts all feed this system, but the balance/statement projections were hard-locked to
-- the viewer's own company. Group-level reviewers (admin, finance manager) need to see
-- every company's rows; the company column travels with each projected row so a mixed
-- view stays attributable.
--
-- The permission is intentionally separate from the per-resource bankdata:*:view codes:
-- seeing your own company's bank data is the default, seeing OTHER companies' bank data
-- is a scope escalation and must be granted explicitly. Users without this permission keep
-- the exact previous behavior (own-company-only, and an explicit companyId filter 403s).

INSERT INTO sys_permission (id, code, name, description) VALUES
    (40, 'bankdata:cross-company:view', '跨公司银行数据查看', 'View bank data projections of all ACTIVE companies, not only the own one');

INSERT INTO sys_role_permission (role_id, permission_id) VALUES
    (1, 40),
    (3, 40);
