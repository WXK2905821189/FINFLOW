package com.finance.system.ai.dto;

import java.time.LocalDateTime;

/**
 * AI 提示词配置视图（V38）。
 *
 * @param capability      能力名（目录 key）
 * @param name            能力中文名
 * @param description     能力说明（提示词作用入口）
 * @param effectivePrompt 当前生效的系统提示词（有覆盖 = 覆盖值，否则 = 默认值）
 * @param customized      是否存在覆盖（false = 正在用系统默认）
 * @param defaultPrompt   系统默认提示词（前端「恢复默认」预览用）
 * @param updatedBy       最后编辑人用户名（覆盖时才有）
 * @param updatedAt       最后编辑时间（覆盖时才有）
 */
public record AiPromptView(
        String capability,
        String name,
        String description,
        String effectivePrompt,
        boolean customized,
        String defaultPrompt,
        String updatedBy,
        LocalDateTime updatedAt
) {
}
