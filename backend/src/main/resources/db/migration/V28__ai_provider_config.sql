-- V28: AI 供应商在线配置（系统管理 → AI 设置页）
--
-- 背景：V27 地基的 provider/密钥/模型全部走环境变量，改配置要进容器重启。
-- 本迁移落地「页面改配置」：管理员在 AI 设置页保存后即时生效（无需重启），
-- 语义为 **DB 配置覆盖 env**：行存在时逐字段覆盖 application.yml 的 ai.*，
-- 行不存在或字段留空则回落 env 值（零配置环境行为与 V27 完全一致）。
--
-- 安全口径：
--   * api_key 以 AES-256-GCM 加密落库（密钥派生自 app.jwt.secret），明文永不回显——
--     读接口只给 api_key_hint（尾 4 位）与"是否已配置"布尔位；
--   * JWT secret 轮换会使已存密文不可解（页面重新保存密钥即可），hint 仍可显示；
--   * capabilities_json 为能力开关快照（per-key 覆盖 env），未登记能力一律关闭。
--
-- 单例行设计：业务上只有一个活跃 provider 配置，固定操作 id=1 行。

CREATE TABLE ai_provider_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    enabled TINYINT(1) NOT NULL DEFAULT 0,
    base_url VARCHAR(255) NULL,
    api_key_cipher VARCHAR(512) NULL,
    api_key_hint VARCHAR(16) NULL,
    model VARCHAR(128) NULL,
    timeout_millis INT NULL,
    max_retries INT NULL,
    daily_limit_per_user INT NULL,
    capabilities_json VARCHAR(2000) NULL,
    updated_by BIGINT NULL,
    updated_at TIMESTAMP NULL DEFAULT NULL,
    created_at TIMESTAMP NULL DEFAULT NULL
);
