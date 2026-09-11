package com.finance.system.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.domain.entity.AiCallLog;
import com.finance.system.domain.mapper.AiCallLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;

/**
 * AI 调用审计（V27 ai_call_log）：写入走 REQUIRES_NEW——审计必须独立于业务事务
 * 成功落库（调用失败/回滚也留下痕迹），沿用 BankDataSyncEvidenceService 的证据链惯例。
 */
@Service
public class AiCallLogService {

    /** 摘要截断长度：库列 1000，这里再留余量。 */
    private static final int SUMMARY_LIMIT = 900;

    private final AiCallLogMapper mapper;

    public AiCallLogService(AiCallLogMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 记录一次调用（成功/失败均落库）。REQUIRES_NEW：调用方即使处于回滚中的事务，
     * 审计行也独立提交。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String capability, Long userId, Long companyId, String provider,
                       String model, String baseUrl, String prompt, String response,
                       String status, String errorMessage, Long durationMs,
                       Integer promptTokens, Integer completionTokens) {
        AiCallLog log = new AiCallLog();
        log.setCapability(capability);
        log.setUserId(userId);
        log.setCompanyId(companyId);
        log.setProvider(provider);
        log.setModel(model);
        log.setBaseUrl(baseUrl);
        log.setPromptHash(sha256(prompt));
        log.setPromptSummary(summarize(prompt));
        log.setResponseHash(sha256(response));
        log.setResponseSummary(summarize(response));
        log.setStatus(status);
        log.setErrorMessage(summarize(errorMessage));
        log.setDurationMs(durationMs);
        log.setPromptTokens(promptTokens);
        log.setCompletionTokens(completionTokens);
        log.setTotalTokens((promptTokens == null ? 0 : promptTokens) + (completionTokens == null ? 0 : completionTokens));
        log.setCreatedBy(userId);
        log.setCreatedAt(LocalDateTime.now());
        mapper.insert(log);
    }

    /** 今日某用户某能力已调用次数（含失败）——限频依据。 */
    public long countToday(String capability, Long userId) {
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        return mapper.selectCount(new LambdaQueryWrapper<AiCallLog>()
                .eq(AiCallLog::getCapability, capability)
                .eq(AiCallLog::getUserId, userId)
                .ge(AiCallLog::getCreatedAt, dayStart));
    }

    /** 最近调用（审计查看，倒序）。 */
    public List<AiCallLog> recent(int limit) {
        return mapper.selectList(new LambdaQueryWrapper<AiCallLog>()
                .orderByDesc(AiCallLog::getId)
                .last("LIMIT " + Math.max(1, Math.min(limit, 200))));
    }

    private static String sha256(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String summarize(String value) {
        if (value == null) {
            return null;
        }
        String flat = value.replaceAll("\\s+", " ").trim();
        return flat.length() <= SUMMARY_LIMIT ? flat : flat.substring(0, SUMMARY_LIMIT) + "...";
    }
}
