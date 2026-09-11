package com.finance.system.ai;

import com.finance.system.ai.dto.AiSelfTestResponse;
import com.finance.system.ai.dto.AiStatusResponse;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.SysUser;
import com.finance.system.domain.mapper.SysUserMapper;
import org.springframework.stereotype.Service;

/**
 * AI 能力守卫与编排（P0 地基 + V28 在线配置）：所有 AI 端点统一走 {@link #guard}——
 * 总开关 → 密钥 → 能力开关 → 限频，任一不过即 403/429（fail-closed）。
 * 配置一律来自 {@link AiConfigService#effective()}（DB 在线配置覆盖 env，保存即生效）。
 *
 * <p>审计在网关调用外层统一做：成功记 SUCCEEDED、任何异常记 FAILED 后原样
 * 重抛，保证"每次调用留全量审计"是编排层不变式而非实现自觉。</p>
 */
@Service
public class AiGatewayService {

    /** 内置连通性自检能力名（能力开关 self-test=true 时开放）。 */
    public static final String SELF_TEST = "self-test";

    private final AiConfigService configService;
    private final LlmGateway llmGateway;
    private final AiCallLogService callLogService;
    private final SysUserMapper userMapper;

    public AiGatewayService(AiConfigService configService, LlmGateway llmGateway,
                            AiCallLogService callLogService, SysUserMapper userMapper) {
        this.configService = configService;
        this.llmGateway = llmGateway;
        this.callLogService = callLogService;
        this.userMapper = userMapper;
    }

    public AiStatusResponse status() {
        AiEffectiveConfig config = configService.effective();
        return new AiStatusResponse(
                config.enabled(),
                config.provider(),
                config.model(),
                config.baseUrl(),
                config.apiKeyConfigured(),
                config.dailyLimitPerUser(),
                config.capabilities());
    }

    /** 连通性自检：真实打一次 LLM 往返（能力未开/网关未启用同样 403）。 */
    public AiSelfTestResponse selfTest(Long userId) {
        AiEffectiveConfig config = auditedGuard(SELF_TEST, userId);
        LlmChatRequest request = new LlmChatRequest(SELF_TEST,
                "你是 FINFLOW 财务系统的连通性探针。无论收到什么，只回复一个词：PONG",
                "ping", 0.0, 16);
        LlmChatResult result = auditedChat(SELF_TEST, userId, config, request);
        return new AiSelfTestResponse(result.content(), result.model(), result.durationMillis(),
                result.promptTokens(), result.completionTokens());
    }

    /**
     * 统一入口：守卫（总开关→密钥→能力开关→限频）→ LLM 往返 → 审计（成功/失败都落库）。
     * 能力服务（A1 入账建议等）只调本方法，不自行触网关、不自行写审计。
     */
    public LlmChatResult auditedChat(String capability, Long userId, AiEffectiveConfig config,
                                     LlmChatRequest request) {
        Long companyId = companyOf(userId);
        long startedAt = System.currentTimeMillis();
        try {
            LlmChatResult result = llmGateway.chat(request, config);
            callLogService.record(capability, userId, companyId, config.provider(),
                    result.model(), config.baseUrl(),
                    request.userPrompt(), result.content(), "SUCCEEDED", null,
                    result.durationMillis(), result.promptTokens(), result.completionTokens());
            return result;
        } catch (Exception e) {
            callLogService.record(capability, userId, companyId, config.provider(),
                    config.model(), config.baseUrl(),
                    request.userPrompt(), null, "FAILED", e.getMessage(),
                    System.currentTimeMillis() - startedAt, null, null);
            throw e;
        }
    }

    /** 守卫 + 返回生效配置（供调用方把配置传给 {@link #auditedChat}）。 */
    public AiEffectiveConfig auditedGuard(String capability, Long userId) {
        guard(capability, userId);
        return configService.effective();
    }

    /** 统一守卫：总开关 → 密钥 → 能力开关 → 每用户每能力日限频（失败调用也计入）。 */
    void guard(String capability, Long userId) {
        AiEffectiveConfig config = configService.effective();
        if (!config.enabled()) {
            throw new BusinessException(403, "AI 能力总开关未启用（AI 设置页或 AI_ENABLED）");
        }
        if (!config.apiKeyConfigured()) {
            throw new BusinessException(403, "AI 已启用但未配置 API 密钥：请到 系统管理→AI设置 填写密钥");
        }
        if (!config.isCapabilityEnabled(capability)) {
            throw new BusinessException(403, "AI 能力未开放：" + capability + "（在 AI 设置页能力开关中显式开启）");
        }
        long used = callLogService.countToday(capability, userId);
        if (used >= config.dailyLimitPerUser()) {
            throw new BusinessException(429, "今日「" + capability + "」调用已达上限（"
                    + config.dailyLimitPerUser() + " 次/日），明天再试或联系管理员调整");
        }
    }

    /** 能力开关快照（前端状态页展示用，key 保序）。 */
    public java.util.Map<String, Boolean> capabilitySnapshot() {
        return new java.util.LinkedHashMap<>(configService.effective().capabilities());
    }

    private Long companyOf(Long userId) {
        SysUser user = userMapper.selectById(userId);
        return user == null ? null : user.getCompanyId();
    }
}
