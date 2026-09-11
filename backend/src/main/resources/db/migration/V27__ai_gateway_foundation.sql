-- V27: AI 能力地基（P0）——LlmGateway 调用审计 + AI 权限域
--
-- 目标：为 AI 能力（规划文档 docs/FINFLOW-AI能力模块规划-20260911.md）打地基：
--   1. ai_call_log：每次 LLM 调用留全量审计（谁、什么能力、送了什么（哈希+脱敏摘要）、
--      返回什么、耗时/token）。密钥与完整 prompt 不落库——摘要截断 + SHA-256 哈希，
--      防止敏感上下文经审计表二次泄漏。
--   2. AI 权限域：ai:use（使用 AI 能力端点）授 ADMIN/FINANCE_STAFF/FINANCE_MANAGER；
--      ai:config（网关状态管理、连通性自检、调用日志查看）授 ADMIN/FINANCE_STAFF。
--   3. 网关本身零表依赖：provider/模型/密钥全部走配置（application.yml ai.*，密钥仅环境
--      变量），能力开关 ai.capabilities.<name>.enabled 默认全关（fail-closed）。
--
-- 限频依赖本表计数：每用户每能力每日上限（AiProperties.dailyLimitPerUser）。

CREATE TABLE ai_call_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    capability VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    company_id BIGINT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    base_url VARCHAR(255) NULL,
    prompt_hash CHAR(64) NULL,
    prompt_summary VARCHAR(1000) NULL,
    response_hash CHAR(64) NULL,
    response_summary VARCHAR(1000) NULL,
    status VARCHAR(16) NOT NULL,
    error_message VARCHAR(1000) NULL,
    duration_ms BIGINT NULL,
    prompt_tokens INT NULL,
    completion_tokens INT NULL,
    total_tokens INT NULL,
    created_by BIGINT NULL,
    created_at TIMESTAMP NULL DEFAULT NULL
);

CREATE INDEX idx_ai_call_log_user_cap ON ai_call_log (user_id, capability, created_at);
CREATE INDEX idx_ai_call_log_created_at ON ai_call_log (created_at);

INSERT INTO sys_permission (id, code, name, description) VALUES
    (42, 'ai:use', '使用 AI 能力', 'Call AI capability endpoints (suggestions, digests, diagnostics)'),
    (43, 'ai:config', '管理 AI 网关', 'View AI gateway status, run connectivity self-test and inspect call logs');

INSERT INTO sys_role_permission (role_id, permission_id) VALUES
    (1, 42),
    (2, 42),
    (3, 42),
    (1, 43),
    (2, 43);
