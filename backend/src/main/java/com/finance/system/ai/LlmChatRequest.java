package com.finance.system.ai;

/**
 * LLM 聊天请求（P0 地基最小面）：系统提示 + 用户提示。
 *
 * <p>P2 的受控工具调用（function calling）在此结构上扩展 tools 字段——先保持最小，
 * 避免地基阶段引入用不到的协议面。</p>
 *
 * @param capability  调用方能力名（审计/限频维度，如 self-test、accounting-suggest）
 * @param systemPrompt 系统提示（只来自系统模板，用户数据一律进 userPrompt）
 * @param userPrompt   用户/场景提示（可能含业务数据——由调用方负责脱敏）
 * @param temperature  采样温度（null=供应商默认；建议类场景建议低温）
 * @param maxTokens    最大输出 token（null=供应商默认）
 */
public record LlmChatRequest(String capability, String systemPrompt, String userPrompt,
                             Double temperature, Integer maxTokens) {
}
