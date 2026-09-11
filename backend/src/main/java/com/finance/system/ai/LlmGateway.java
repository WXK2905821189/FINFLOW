package com.finance.system.ai;

/**
 * LLM 网关端口：AI 能力唯一出海口。
 *
 * <p>能力服务（A1 入账建议 / A2 查数 / A3 排障…）只依赖本接口，不感知具体供应商；
 * 实现侧（{@link OpenAiCompatibleLlmGateway}）负责协议、超时、重试与异常翻译。
 * 审计不在网关内做——由 {@link AiGatewayService} 统一编排（成功/失败都落
 * ai_call_log），保证"每次调用留全量审计"不依赖实现自觉。</p>
 */
public interface LlmGateway {

    LlmChatResult chat(LlmChatRequest request);
}
