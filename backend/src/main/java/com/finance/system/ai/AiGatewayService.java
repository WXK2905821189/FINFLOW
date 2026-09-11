package com.finance.system.ai;

import com.finance.system.ai.dto.AiSelfTestResponse;
import com.finance.system.ai.dto.AiStatusResponse;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.SysUser;
import com.finance.system.domain.mapper.SysUserMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 能力守卫与编排（P0 地基）：所有 AI 端点统一走 {@link #guard}——
 * 总开关 → 能力开关 → 限频，任一不过即 403/429（fail-closed）。
 *
 * <p>审计在网关调用外层统一做：成功记 SUCCEEDED、任何异常记 FAILED 后原样
 * 重抛，保证"每次调用留全量审计"是编排层不变式而非实现自觉。</p>
 */
@Service
public class AiGatewayService {

    /** 内置连通性自检能力名（ai.capabilities.self-test=true 时开放）。 */
    public static final String SELF_TEST = "self-test";

    private final AiProperties properties;
    private final LlmGateway llmGateway;
    private final AiCallLogService callLogService;
    private final SysUserMapper userMapper;

    public AiGatewayService(AiProperties properties, LlmGateway llmGateway,
                            AiCallLogService callLogService, SysUserMapper userMapper) {
        this.properties = properties;
        this.llmGateway = llmGateway;
        this.callLogService = callLogService;
        this.userMapper = userMapper;
    }

    public AiStatusResponse status() {
        return new AiStatusResponse(
                properties.isEnabled(),
                properties.getProvider(),
                properties.getModel(),
                properties.getBaseUrl(),
                properties.getApiKey() != null && !properties.getApiKey().isBlank(),
                properties.getDailyLimitPerUser(),
                properties.getCapabilities());
    }

    /** 连通性自检：真实打一次 LLM 往返（能力未开/网关未启用同样 403）。 */
    public AiSelfTestResponse selfTest(Long userId) {
        guard(SELF_TEST, userId);
        Long companyId = companyOf(userId);
        LlmChatRequest request = new LlmChatRequest(SELF_TEST,
                "你是 FINFLOW 财务系统的连通性探针。无论收到什么，只回复一个词：PONG",
                "ping", 0.0, 16);
        long startedAt = System.currentTimeMillis();
        try {
            LlmChatResult result = llmGateway.chat(request);
            callLogService.record(SELF_TEST, userId, companyId, properties.getProvider(),
                    properties.getModel(), properties.getBaseUrl(),
                    request.userPrompt(), result.content(), "SUCCEEDED", null,
                    result.durationMillis(), result.promptTokens(), result.completionTokens());
            return new AiSelfTestResponse(result.content(), result.model(), result.durationMillis(),
                    result.promptTokens(), result.completionTokens());
        } catch (Exception e) {
            callLogService.record(SELF_TEST, userId, companyId, properties.getProvider(),
                    properties.getModel(), properties.getBaseUrl(),
                    request.userPrompt(), null, "FAILED", e.getMessage(),
                    System.currentTimeMillis() - startedAt, null, null);
            throw e;
        }
    }

    /** 统一守卫：总开关 → 能力开关 → 每用户每能力日限频（失败调用也计入）。 */
    void guard(String capability, Long userId) {
        if (!properties.isEnabled()) {
            throw new BusinessException(403, "AI 能力总开关未启用（ai.enabled=false）");
        }
        if (!properties.isCapabilityEnabled(capability)) {
            throw new BusinessException(403, "AI 能力未开放：" + capability + "（在 ai.capabilities 中显式开启）");
        }
        long used = callLogService.countToday(capability, userId);
        if (used >= properties.getDailyLimitPerUser()) {
            throw new BusinessException(429, "今日「" + capability + "」调用已达上限（"
                    + properties.getDailyLimitPerUser() + " 次/日），明天再试或联系管理员调整");
        }
    }

    /** 能力开关快照（前端状态页展示用，key 保序）。 */
    public Map<String, Boolean> capabilitySnapshot() {
        return new LinkedHashMap<>(properties.getCapabilities());
    }

    private Long companyOf(Long userId) {
        SysUser user = userMapper.selectById(userId);
        return user == null ? null : user.getCompanyId();
    }
}
