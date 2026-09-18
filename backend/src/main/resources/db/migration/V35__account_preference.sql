-- V35 (2026-09-18): Excel grid kernel — account-level UI preference store
--
-- Design source: docs/ui-v34-demo.html §V35 表格内核, kernel header 口径③:
--   「视图偏好 = 服务端账号级（跨设备一致），不是本机 localStorage。」
-- The demo showed the intended UX; this table is what makes 口径③ real in the product.
--
-- Semantics:
--   user_id    owner account (preferences never跨账号共享；同一账号多设备登录共享一份)
--   scope_key  网格实例标识，形如 grid.balance / grid.statements。前端按 scope 读写，
--              新增网格只需新 scope_key，不再加表。
--   payload    前端构建的**不透明 JSON 快照**：可见列、列序、列宽、排序、本页列头筛选、
--              行密度、冻结列数、命名视图列表。服务端只校验「是合法 JSON + 长度上限」，
--              不解释结构 —— 结构演进属前端，避免为每个字段加一次迁移。
--   updated_at NULL = 从未编辑过（读接口据此区分「没有偏好」与「偏好为空对象」）。
--
-- Notes:
--   payload TEXT(64KB)：一个快照 ~1-2KB，留足余量；不用 LONGTEXT（最大 4GB）当垃圾桶。
--   uk(user_id, scope_key) 是 upsert 键，保存走「先查后更」，并发下重复插入由唯一键兜底。

CREATE TABLE account_preference (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    scope_key VARCHAR(128) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT NULL,
    UNIQUE KEY uk_account_preference_user_scope (user_id, scope_key)
);
