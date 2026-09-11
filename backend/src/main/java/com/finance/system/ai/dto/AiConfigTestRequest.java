package com.finance.system.ai.dto;

import jakarta.validation.constraints.Size;

/**
 * POST /api/ai/config/test —— 用「表单当前值（可能尚未保存）」做连通性测试。
 * 三个字段均可选：apiKey 为 null/空 = 用已生效密钥；非空 = 用表单输入的新值（只在
 * 本次请求内存中使用，绝不落库/落日志/落审计明文）。
 */
public record AiConfigTestRequest(
        @Size(max = 255) String baseUrl,
        @Size(max = 256) String apiKey,
        @Size(max = 128) String model) {
}
