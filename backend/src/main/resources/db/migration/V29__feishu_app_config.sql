-- V29: 飞书自建应用凭证在线配置（飞书协同 → 连接向导）
--
-- 背景：V10 飞书协同为纯 mock（不读取/保存真实凭证）。本迁移为「真实连接」
-- 打地基：页面连接向导引导管理员创建飞书自建应用，填入 App ID / App Secret
-- 并真实调飞书 tenant_access_token/internal 验证。
--
-- 安全口径（与 V28 ai_provider_config 完全一致）：
--   * app_secret 以 AES-256-GCM 加密落库（密钥派生自 app.jwt.secret），明文永不回显——
--     读接口只给 app_secret_hint（尾 4 位）与"是否已配置"布尔位；
--   * JWT secret 轮换会使已存密文不可解（页面重新保存密钥即可），hint 仍可显示。
--
-- 单例行设计：业务上只有一个活跃自建应用配置，固定操作 id=1 行。

CREATE TABLE feishu_app_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    app_id VARCHAR(128) NULL,
    app_secret_cipher VARCHAR(512) NULL,
    app_secret_hint VARCHAR(16) NULL,
    verified_at TIMESTAMP NULL DEFAULT NULL,
    verified_by BIGINT NULL,
    verified_tenant VARCHAR(255) NULL,
    updated_by BIGINT NULL,
    updated_at TIMESTAMP NULL DEFAULT NULL,
    created_at TIMESTAMP NULL DEFAULT NULL
);
