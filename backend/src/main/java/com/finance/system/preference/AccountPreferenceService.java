package com.finance.system.preference;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.AccountPreference;
import com.finance.system.domain.mapper.AccountPreferenceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 账号级界面偏好服务（V35）。
 *
 * <p>对应 V35 表格内核口径③：视图偏好存服务端账号级、跨设备一致。服务端对 payload
 * <strong>只做形态校验</strong>（合法 JSON + 长度上限），不解释内部结构——列布局结构属前端契约，
 * 放服务端解释会让「加一个筛选字段」变成一次迁移。</p>
 *
 * <p>读写都按 {@code (userId, scopeKey)} 定位，天然不可能读到别人的偏好。</p>
 */
@Service
public class AccountPreferenceService {

    /** scope 形如 grid.balance；限定字符集，避免把任意串写进索引列。 */
    private static final Pattern SCOPE_KEY_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    /** payload 上限 20,000 字符（快照实际 ~1-2KB）；超限说明前端把不该存的东西塞了进来。 */
    private static final int MAX_PAYLOAD_LENGTH = 20_000;

    private final AccountPreferenceMapper accountPreferenceMapper;
    private final ObjectMapper objectMapper;

    public AccountPreferenceService(AccountPreferenceMapper accountPreferenceMapper, ObjectMapper objectMapper) {
        this.accountPreferenceMapper = accountPreferenceMapper;
        this.objectMapper = objectMapper;
    }

    /** 读取；未保存过返回 {@code absent}（200 + payload=null），不是错误。 */
    @Transactional(readOnly = true)
    public AccountPreferenceResponse get(Long userId, String scopeKey) {
        String normalized = normalizeScopeKey(scopeKey);
        AccountPreference row = accountPreferenceMapper.selectOne(new LambdaQueryWrapper<AccountPreference>()
                .eq(AccountPreference::getUserId, userId)
                .eq(AccountPreference::getScopeKey, normalized));
        if (row == null) {
            return AccountPreferenceResponse.absent(normalized);
        }
        return new AccountPreferenceResponse(normalized, row.getPayload(), row.getUpdatedAt());
    }

    /** 保存（存在则更新，不存在则插入）。 */
    @Transactional
    public AccountPreferenceResponse save(Long userId, String scopeKey, String payload) {
        String normalized = normalizeScopeKey(scopeKey);
        String validated = validatePayload(payload);

        AccountPreference existing = accountPreferenceMapper.selectOne(new LambdaQueryWrapper<AccountPreference>()
                .eq(AccountPreference::getUserId, userId)
                .eq(AccountPreference::getScopeKey, normalized));

        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            AccountPreference row = new AccountPreference();
            row.setUserId(userId);
            row.setScopeKey(normalized);
            row.setPayload(validated);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            accountPreferenceMapper.insert(row);
            return new AccountPreferenceResponse(normalized, validated, now);
        }

        existing.setPayload(validated);
        existing.setUpdatedAt(now);
        accountPreferenceMapper.updateById(existing);
        return new AccountPreferenceResponse(normalized, validated, now);
    }

    private String normalizeScopeKey(String scopeKey) {
        if (scopeKey == null || !SCOPE_KEY_PATTERN.matcher(scopeKey).matches()) {
            throw new BusinessException(400, "scope 不合法：仅允许字母/数字/点/下划线/短横，长度 1-128");
        }
        return scopeKey;
    }

    private String validatePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new BusinessException(400, "payload 不能为空");
        }
        if (payload.length() > MAX_PAYLOAD_LENGTH) {
            throw new BusinessException(400, "payload 超长（上限 " + MAX_PAYLOAD_LENGTH + " 字符）");
        }
        try {
            objectMapper.readTree(payload);
        } catch (Exception exception) {
            throw new BusinessException(400, "payload 必须是合法 JSON");
        }
        return payload;
    }
}
