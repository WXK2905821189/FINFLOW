package com.finance.system.preference;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.AccountPreference;
import com.finance.system.domain.mapper.AccountPreferenceMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V35 账号级界面偏好：读写的形态校验与 upsert 分支。
 *
 * <p>刻意只测服务层——"payload 是不透明 JSON、服务端不解释结构" 这条契约的边界全部在这里，
 * 用 Mockito 比起 H2 更能把边界钉住。</p>
 */
@ExtendWith(MockitoExtension.class)
class AccountPreferenceServiceTest {

    private static final Long USER_ID = 7L;
    private static final String SCOPE = "grid.balance";

    @Mock
    private AccountPreferenceMapper mapper;

    private AccountPreferenceService service() {
        return new AccountPreferenceService(mapper, new ObjectMapper());
    }

    @Test
    void get_returnsAbsentWhenNeverSaved() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        AccountPreferenceResponse response = service().get(USER_ID, SCOPE);

        assertThat(response.scopeKey()).isEqualTo(SCOPE);
        assertThat(response.payload()).isNull();
        assertThat(response.updatedAt()).isNull();
    }

    @Test
    void get_returnsStoredPayload() {
        AccountPreference row = new AccountPreference();
        row.setScopeKey(SCOPE);
        row.setPayload("{\"on\":[\"bankName\"]}");
        row.setUpdatedAt(LocalDateTime.of(2026, 9, 18, 10, 0));
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(row);

        AccountPreferenceResponse response = service().get(USER_ID, SCOPE);

        assertThat(response.payload()).isEqualTo("{\"on\":[\"bankName\"]}");
        assertThat(response.updatedAt()).isEqualTo(LocalDateTime.of(2026, 9, 18, 10, 0));
    }

    @Test
    void save_insertsWhenNoExistingRow() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        AccountPreferenceResponse response = service().save(USER_ID, SCOPE, "{\"frozen\":1}");

        assertThat(response.payload()).isEqualTo("{\"frozen\":1}");
        ArgumentCaptor<AccountPreference> captor = ArgumentCaptor.forClass(AccountPreference.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().getScopeKey()).isEqualTo(SCOPE);
        assertThat(captor.getValue().getCreatedAt()).isNotNull();
        verify(mapper, never()).updateById(any(AccountPreference.class));
    }

    @Test
    void save_updatesExistingRowInPlace() {
        AccountPreference existing = new AccountPreference();
        existing.setId(99L);
        existing.setUserId(USER_ID);
        existing.setScopeKey(SCOPE);
        existing.setPayload("{\"frozen\":0}");
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        AccountPreferenceResponse response = service().save(USER_ID, SCOPE, "{\"frozen\":2}");

        assertThat(response.payload()).isEqualTo("{\"frozen\":2}");
        assertThat(existing.getPayload()).isEqualTo("{\"frozen\":2}");
        assertThat(existing.getUpdatedAt()).isNotNull();
        verify(mapper).updateById(existing);
        verify(mapper, never()).insert(any(AccountPreference.class));
    }

    @Test
    void rejectsIllegalScopeKey() {
        assertThatThrownBy(() -> service().get(USER_ID, "grid/balance"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("scope");

        assertThatThrownBy(() -> service().save(USER_ID, "x".repeat(129), "{}"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("scope");

        verify(mapper, never()).selectOne(any(LambdaQueryWrapper.class));
    }

    @Test
    void rejectsNonJsonPayload() {
        assertThatThrownBy(() -> service().save(USER_ID, SCOPE, "not-json"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("JSON");
    }

    @Test
    void rejectsBlankAndOverlongPayload() {
        assertThatThrownBy(() -> service().save(USER_ID, SCOPE, "  "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能为空");

        assertThatThrownBy(() -> service().save(USER_ID, SCOPE, "{\"a\":\"" + "x".repeat(20_000) + "\"}"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超长");
    }
}
