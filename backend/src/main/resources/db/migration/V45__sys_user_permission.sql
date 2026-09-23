-- V45（2026-09-23，W17 包 D）：账号级权限覆盖 —— sys_user_permission
--
-- 背景：角色是权限的唯一来源，个别账号需要「比角色多一点 / 少一点」的微调，
--   为此单开角色会产生角色爆炸。本表在角色权限之上叠加账号级覆盖：
--
-- 语义：
--   user_id          目标账号（覆盖只对该账号生效，不影响同角色其他人）
--   permission_code  权限编码（必须存在于 sys_permission 目录，服务端校验，瞎编 400）
--   effect           GRANT（角色没有也授予）/ DENY（角色有也剔除）
--   created_by       操作人（超管）
--   created_at       创建时间
--
-- 生效公式（RbacService.permissionCodesForUser）：
--   有效权限 = (角色权限 ∪ 账号 GRANT) − 账号 DENY
-- 权限无缓存层（authorities 每请求由 UserDetailsServiceImpl 实时加载），覆盖保存即生效。
--
-- uk(user_id, permission_code)：同一账号同一权限只允许一条覆盖；
-- GRANT 与 DENY 互斥由服务端保证（同一请求内同 code 出现两条 → 400）。
-- 防自锁守卫（服务端）：超管账号 / 操作人本人不允许 DENY role:manage（409）。

CREATE TABLE sys_user_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    permission_code VARCHAR(64) NOT NULL,
    effect VARCHAR(8) NOT NULL,
    created_by BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_perm (user_id, permission_code)
);

CREATE INDEX idx_sys_user_permission_code ON sys_user_permission (permission_code);
