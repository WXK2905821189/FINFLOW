-- V38 (2026-09-20): AI 能力提示词覆盖（W9 需求 4）
--
-- 背景：三个 AI 能力（accounting-suggestion / company-classification / rule-import）
-- 的系统提示词此前硬编码在各 Service 常量里，想调措辞只能改代码发版。
-- 本迁移落地「页面改提示词」：每个 AI 能力入口旁给超管（ai:config）一个设置按钮，
-- 支持编辑与一键重置（重置 = 删覆盖行，回落代码内默认值）。
--
-- 语义：
--   capability    能力名，与 ai_call_log.action / 能力开关同域；只允许目录内已登记的 key。
--   system_prompt 覆盖后的系统提示词（TEXT，上限 20000 字符，服务端校验）。
--   updated_by    最后编辑人（审计可追）。
--
-- 读取口径（AiPromptService.resolve）：行存在且内容非空 → 用覆盖值；
-- 行不存在 / 被重置删除 → 回落代码默认。提示词是全局生效的系统配置，
-- 与 ai_provider_config（供应商连接配置）分离，互不覆盖。
--
-- Notes:
--   uk(capability) 是 upsert 键，保存走「先查后更」，并发重复插入由唯一键兜底。

CREATE TABLE ai_prompt_override (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    capability VARCHAR(64) NOT NULL,
    system_prompt TEXT NOT NULL,
    updated_by BIGINT NOT NULL,
    updated_at TIMESTAMP NULL DEFAULT NULL,
    created_at TIMESTAMP NULL DEFAULT NULL,
    UNIQUE KEY uk_ai_prompt_override_capability (capability)
);
