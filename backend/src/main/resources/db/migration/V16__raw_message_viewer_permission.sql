-- V16: raw bank message viewer permission
--
-- The raw message table (V4) has been capturing every bank response since the
-- pipeline went live, but nothing could read it back: `bank-data-trace`
-- deliberately exposes digests only and is keyed to a single task, so there was
-- no way to browse or evidence "did we actually reach the bank".
--
-- This adds a standalone viewer permission for the new raw message module. It is
-- intentionally separate from `bankdata:view` because this module is the only
-- surface that hands out the full response payload - the digest-only rule that
-- governs the rest of the pipeline does not apply here, so access is granted
-- explicitly rather than inherited.

INSERT INTO sys_permission (id, code, name, description) VALUES
    (39, 'bankdata:raw:view', '查看银行原始报文', 'View and download raw bank response payloads as connectivity evidence');

INSERT INTO sys_role_permission (role_id, permission_id) VALUES
    (1, 39),
    (2, 39);
