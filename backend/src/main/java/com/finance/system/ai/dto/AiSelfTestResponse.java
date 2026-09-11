package com.finance.system.ai.dto;

/** 连通性自检结果（POST /api/ai/self-test）：LLM 原文回信 + 耗时/token，全程已审计。 */
public record AiSelfTestResponse(String reply, String model, long durationMillis,
                                 Integer promptTokens, Integer completionTokens) {
}
