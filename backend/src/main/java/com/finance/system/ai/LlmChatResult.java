package com.finance.system.ai;

/**
 * LLM 聊天结果（OpenAI 兼容响应的规范化投影）。
 *
 * @param content          首个 choice 的消息文本
 * @param model            实际使用的模型名
 * @param promptTokens     输入 token（供应商未回 usage 时为 null）
 * @param completionTokens 输出 token（同上）
 * @param durationMillis   本次调用总耗时（含重试）
 */
public record LlmChatResult(String content, String model,
                            Integer promptTokens, Integer completionTokens,
                            long durationMillis) {

    public int totalTokens() {
        int p = promptTokens == null ? 0 : promptTokens;
        int c = completionTokens == null ? 0 : completionTokens;
        return p + c;
    }
}
