package com.finance.system.operations;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.bankdata.adapter.BankAdapterExecutionMode;
import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.bankdata.adapter.BankDataCollection;
import com.finance.system.bankdata.adapter.BankDataSyncContext;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provider-level connection status when a REAL adapter is actually assembled.
 *
 * <p>Guards the bug where the console reported "CMB connected" for EVERY row: status must be
 * derived per provider from adapter assembly, and a persisted {@code connection_profile.status}
 * that has drifted (here: CITIC is stored as REAL/enabled) must be overridden to NOT_CONNECTED.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Transactional
@TestPropertySource(properties = "bankdata.adapter.call.real-adapters-enabled=true")
@Import(ConnectionOperationsRealProviderTest.StubRealCmbAdapterConfiguration.class)
class ConnectionOperationsRealProviderTest {

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
        // CMB wired for real; CITIC stored as REAL/enabled to prove the DB column cannot win.
        persistProfile("CMB-REAL", "招行直联", "CMB", true, "NOT_ENABLED");
        persistProfile("CITIC-REAL", "中信直联(未装配)", "CITIC", true, "REAL");
    }

    @Test
    void overviewReportsTheWiredBankAndKeepsUnwiredBanksNotConnected() {
        ConnectionOverviewResponse overview = service.overview(adminId);

        assertTrue(overview.enabled());
        assertEquals("REAL", overview.status());
        assertTrue(overview.message().contains("CMB"), "message names the bank that is actually wired");
        assertFalse(overview.message().contains("CITIC"), "unwired banks must not be advertised");

        Map<String, ConnectionSummaryResponse> rows = byCode(overview.connections());
        assertTrue(rows.get("CMB-REAL").enabled());
        assertEquals("REAL", rows.get("CMB-REAL").status());
        assertFalse(rows.get("CITIC-REAL").enabled());
        assertEquals("NOT_CONNECTED", rows.get("CITIC-REAL").status(),
                "stored status 'REAL' must not survive when no REAL adapter is assembled");
    }

    @Test
    void configurationListsOnlyWiredProvidersAsSupported() {
        ConnectionConfigurationResponse configuration = service.configuration(adminId, null);

        assertTrue(configuration.enabled());
        assertEquals(List.of("CMB"), configuration.supportedProviderTypes());
        assertTrue(configuration.message().contains("CMB"));

        Map<String, ConnectionSummaryResponse> rows = byCode(configuration.connections());
        assertEquals("REAL", rows.get("CMB-REAL").status());
        assertEquals("NOT_CONNECTED", rows.get("CITIC-REAL").status());
    }

    @Test
    void dataCapabilityIsRealWhenAtLeastOneRealAdapterExists() {
        DataQueryCapabilityResponse capability = service.dataCapability("balances");

        assertTrue(capability.enabled());
        assertEquals("REAL", capability.status());
        assertTrue(capability.message().contains("CMB"));
    }

    private Map<String, ConnectionSummaryResponse> byCode(List<ConnectionSummaryResponse> rows) {
        return rows.stream().collect(Collectors.toMap(ConnectionSummaryResponse::connectionCode, Function.identity()));
    }

    private void persistProfile(String code, String displayName, String providerType, boolean enabled, String status) {
        // Plain codes: each test runs in its own rolled-back transaction, so uniqueness holds.
        ConnectionProfile profile = new ConnectionProfile();
        profile.setCompanyId(COMPANY_ID);
        profile.setConnectionCode(code);
        profile.setDisplayName(displayName);
        profile.setProviderType(providerType);
        profile.setEnabled(enabled);
        profile.setStatus(status);
        profileMapper.insert(profile);
    }

    /** Deterministic REAL-mode CMB adapter: makes "CMB is assembled" true, CITIC stays false. */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubRealCmbAdapterConfiguration {

        @Bean
        BankDataAdapter stubRealCmbAdapter() {
            return new BankDataAdapter() {
                @Override
                public String adapterCode() {
                    return "CMB";
                }

                @Override
                public BankAdapterExecutionMode executionMode() {
                    return BankAdapterExecutionMode.REAL;
                }

                @Override
                public BankDataCollection collect(BankDataSyncContext context) {
                    return new BankDataCollection("stub-cmb", List.of(), List.of(), false, null,
                            "SUC0000", "SUC0000");
                }
            };
        }
    }
}
