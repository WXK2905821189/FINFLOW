package com.finance.system.ai;

import java.util.List;

/**
 * LLM 网关端口：AI 能力唯一出海口。
 *
 * <p>能力服务（A1 入账建议 / A2 查数 / A3 排障…）只依赖本接口，不感知具体供应商；
 * 实现侧（{@link OpenAiCompatibleLlmGateway}）负责协议、超时、重试与异常翻译。
 * V28 起调用方显式传入 {@link AiEffectiveConfig}（DB 在线配置覆盖 env 的合并快照），
 * 设置页保存即时生效、无需重启。审计不在网关内做——由 {@link AiGatewayService}
 * 统一编排（成功/失败都落 ai_call_log），保证"每次调用留全量审计"不依赖实现自觉。</p>
 */
public interface LlmGateway {

    LlmChatResult chat(LlmChatRequest request, AiEffectiveConfig config);

    /**
     * 拉取供应商可用模型列表（OpenAI 兼容协议 GET {base-url}/models，Bearer 密钥），
     * 供 AI 设置页在填入接入点与密钥后让用户直接选择模型。配置面查询，
     * 不做审计、不消耗能力调用。
     */
    List<String> listModels(AiEffectiveConfig config);
}
