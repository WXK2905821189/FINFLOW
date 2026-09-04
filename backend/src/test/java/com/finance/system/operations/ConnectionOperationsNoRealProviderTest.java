package com.finance.system.operations;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.domain.entity.ConnectionProfile;
import com.finance.system.domain.entity.SysUser;
import com.finance.system.domain.mapper.ConnectionProfileMapper;
import com.finance.system.domain.mapper.SysUserMapper;
import com.finance.system.operations.dto.ConnectionConfigurationResponse;
import com.finance.system.operations.dto.ConnectionOverviewResponse;
import com.finance.system.operations.dto.ConnectionSummaryResponse;
import com.finance.system.operations.dto.DataQueryCapabilityResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provider-level connection status when NO real adapter is assembled - the drift guard.
 *
 * <p>This is the exact "为什么没接入中信却显示连接成功" scenario: the profile row claims
 * {@code status='REAL', enabled=true} for CMB while the server has not assembled any REAL adapter.
 * Status must come from assembly, so the row must read NOT_CONNECTED and the overview must say the
 * truth - nothing is connected.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Transactional
class ConnectionOperationsNoRealProviderTest {

    private static final long COMPANY_ID = 1L;

    @Autowired
    private ConnectionOperationsService service;

    @Autowired
    private ConnectionProfileMapper profileMapper;

    @Autowired
    private SysUserMapper userMapper;

    private Long adminId;

    @BeforeEach
    void setUp() {
        adminId = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, "admin")).getId();
        // Deliberately optimistic stored state: the DB claims CMB is REAL and enabled.
        persistProfile("CMB-STALE", "招行直联(库里写 REAL)", "CMB", true, "REAL");
    }

    @Test
    void storedRealStatusIsOverriddenWhenNoRealAdapterIsAssembled() {
        ConnectionOverviewResponse overview = service.overview(adminId);

        assertFalse(overview.enabled(), "no REAL adapter assembled means nothing is connected");
        assertEquals("DISABLED", overview.status());
        assertTrue(overview.message().contains("未连接"));

        ConnectionSummaryResponse row = overview.connections().stream()
                .filter(item -> "CMB-STALE".equals(item.connectionCode())).findFirst().orElseThrow();
        assertFalse(row.enabled());
        assertEquals("NOT_CONNECTED", row.status(),
                "a stale stored 'REAL' must never be shown as connected");
    }

    @Test
    void configurationReportsNoSupportedProviders() {
        ConnectionConfigurationResponse configuration = service.configuration(adminId, null);

        assertFalse(configuration.enabled());
        assertEquals("NOT_CONFIGURED", configuration.status());
        assertTrue(configuration.supportedProviderTypes().isEmpty());
        assertTrue(configuration.message().contains("未连接"));
    }

    @Test
    void dataCapabilityIsNotRealWithoutARealAdapter() {
        DataQueryCapabilityResponse capability = service.dataCapability("statements");

        assertFalse(capability.enabled());
        assertEquals("NOT_CONFIGURED", capability.status());
    }

    private Map<String, ConnectionSummaryResponse> byCode(List<ConnectionSummaryResponse> rows) {
        return rows.stream().collect(Collectors.toMap(ConnectionSummaryResponse::connectionCode, Function.identity()));
    }

    private void persistProfile(String code, String displayName, String providerType, boolean enabled, String status) {
        ConnectionProfile profile = new ConnectionProfile();
        profile.setCompanyId(COMPANY_ID);
        profile.setConnectionCode(code);
        profile.setDisplayName(displayName);
        profile.setProviderType(providerType);
        profile.setEnabled(enabled);
        profile.setStatus(status);
        profileMapper.insert(profile);
    }
}
