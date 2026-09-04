package com.finance.system.operations;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finance.system.bankdata.adapter.BankAdapterExecutionMode;
import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.bankdata.adapter.BankDataCollection;
import com.finance.system.bankdata.adapter.BankDataSyncContext;
import com.finance.system.bankdata.aggregation.BankDataAdapterRegistry;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.common.tenant.CompanyScopeService;
import com.finance.system.domain.entity.ConnectionOperationLog;
import com.finance.system.domain.entity.ConnectionProfile;
import com.finance.system.domain.mapper.ConnectionOperationLogMapper;
import com.finance.system.domain.mapper.ConnectionOperationTaskMapper;
import com.finance.system.domain.mapper.ConnectionProfileMapper;
import com.finance.system.operations.dto.ConnectionConfigurationResponse;
import com.finance.system.operations.dto.ConnectionOverviewResponse;
import com.finance.system.operations.dto.ConnectionSummaryResponse;
import com.finance.system.operations.dto.DataQueryCapabilityResponse;
import com.finance.system.operations.dto.OperationLogResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Contract tests for the operations console projections (P2-2 coverage gap): provider status is
 * derived from REAL-mode adapter assembly and must never be taken from the persisted
 * connection_profile.status column; real-bank wording must list the actually assembled banks
 * dynamically (no hardcoded bank name); data-capability is limited to balances/statements.
 */
class ConnectionOperationsServiceTest {

    private static final long COMPANY_ID = 42L;
    private static final long USER_ID = 7L;

    private ConnectionProfileMapper profileMapper;
    private ConnectionOperationTaskMapper taskMapper;
    private ConnectionOperationLogMapper logMapper;
    private ConnectionOperationsService service;

    @BeforeEach
    void setUp() {
        profileMapper = mock(ConnectionProfileMapper.class);
        taskMapper = mock(ConnectionOperationTaskMapper.class);
        logMapper = mock(ConnectionOperationLogMapper.class);
        CompanyScopeService companyScope = mock(CompanyScopeService.class);
        when(companyScope.companyIdForUser(USER_ID)).thenReturn(COMPANY_ID);
        service = new ConnectionOperationsService(profileMapper, taskMapper, logMapper,
                companyScope, new BankDataAdapterRegistry(List.of()));
    }

    private void wireRealAdapters(BankDataAdapter... adapters) {
        CompanyScopeService companyScope = mock(CompanyScopeService.class);
        when(companyScope.companyIdForUser(USER_ID)).thenReturn(COMPANY_ID);
        service = new ConnectionOperationsService(profileMapper, taskMapper, logMapper,
                companyScope, new BankDataAdapterRegistry(List.of(adapters)));
    }

    private static ConnectionProfile profile(String code, String provider, String persistedStatus) {
        ConnectionProfile profile = new ConnectionProfile();
        profile.setId(1L);
        profile.setCompanyId(COMPANY_ID);
        profile.setConnectionCode(code);
        profile.setDisplayName(code + "-display");
        profile.setProviderType(provider);
        profile.setStatus(persistedStatus);
        profile.setLastCheckedAt(LocalDateTime.of(2026, 9, 1, 10, 0));
        return profile;
    }

    @Test
    void configurationIsNotConfiguredWithoutAnyRealAdapter() {
        when(profileMapper.selectList(any())).thenReturn(List.of());
        ConnectionConfigurationResponse response = service.configuration(USER_ID, null);
        assertFalse(response.enabled());
        assertEquals("NOT_CONFIGURED", response.status());
        assertTrue(response.supportedProviderTypes().isEmpty());
    }

    @Test
    void configurationDerivesRealStatusFromAssemblyNotFromPersistedColumn() {
        // The stored status column says DISABLED, but a REAL adapter is wired: assembly wins
        // (the persisted column is only ever shown as metadata and may drift from reality).
        ConnectionProfile profile = profile("cmb-001", "CMB", "DISABLED");
        when(profileMapper.selectList(any())).thenReturn(List.of(profile));
        wireRealAdapters(new StubAdapter("CMB", BankAdapterExecutionMode.REAL));

        ConnectionConfigurationResponse response = service.configuration(USER_ID, "applications");
        assertTrue(response.enabled());
        assertEquals("REAL", response.status());
        assertEquals(List.of("CMB"), response.supportedProviderTypes());
        assertTrue(response.message().contains("CMB"));

        ConnectionSummaryResponse row = response.connections().get(0);
        assertTrue(row.enabled());
        assertEquals("REAL", row.status());
    }

    @Test
    void configurationMessageListsAllAssembledBanksDynamically() {
        // Guard against a regression where the copy hardcodes a single bank name.
        when(profileMapper.selectList(any())).thenReturn(List.of());
        wireRealAdapters(new StubAdapter("CMB", BankAdapterExecutionMode.REAL),
                new StubAdapter("CITIC", BankAdapterExecutionMode.REAL));

        ConnectionConfigurationResponse response = service.configuration(USER_ID, null);
        assertTrue(response.enabled());
        assertEquals(List.of("CITIC", "CMB"), response.supportedProviderTypes());
        assertTrue(response.message().contains("CMB"));
        assertTrue(response.message().contains("CITIC"));
    }

    @Test
    void configurationRejectsUnknownSection() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.configuration(USER_ID, "not-a-section"));
        assertEquals(400, error.getCode());
    }

    @Test
    void overviewDistinguishesNotEnabledFromDisabledFromReal() {
        when(profileMapper.selectList(any())).thenReturn(List.of());
        assertEquals("NOT_ENABLED", service.overview(USER_ID).status());

        when(profileMapper.selectList(any())).thenReturn(
                List.of(profile("cmb-001", "CMB", "ACTIVE")));
        ConnectionOverviewResponse disabled = service.overview(USER_ID);
        assertEquals("DISABLED", disabled.status());
        assertFalse(disabled.enabled());

        wireRealAdapters(new StubAdapter("CMB", BankAdapterExecutionMode.REAL));
        ConnectionOverviewResponse real = service.overview(USER_ID);
        assertEquals("REAL", real.status());
        assertTrue(real.enabled());
    }

    @Test
    void dataCapabilitySupportsOnlyRealResources() {
        wireRealAdapters(new StubAdapter("CMB", BankAdapterExecutionMode.REAL));

        DataQueryCapabilityResponse balances = service.dataCapability("balances");
        assertEquals("balances", balances.capability());
        assertTrue(balances.enabled());
        assertEquals("REAL", balances.status());

        assertEquals("statements", service.dataCapability(" statements ").capability());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.dataCapability("receipts"));
        assertEquals(404, error.getCode());
        assertTrue(error.getMessage().contains("balances"));
    }

    @Test
    void logsFilterByConnectionAndSanitizeSensitiveFields() {
        ConnectionProfile profile = profile("cmb-001", "CMB", "ACTIVE");
        when(profileMapper.selectOne(any())).thenReturn(profile);
        when(taskMapper.selectList(any())).thenReturn(List.of());

        // connection exists but has no tasks yet -> empty page, no mapper hit on logMapper
        var empty = service.logs(USER_ID, 1, 10, "cmb-001", null, null);
        assertEquals(0, empty.total());

        ConnectionOperationLog log = new ConnectionOperationLog();
        log.setTaskId(9L);
        log.setLevel("WARN");
        log.setEventType("SYNC");
        log.setResult("SUCCEEDED");
        log.setRequestId("REQ-1");
        log.setMessage("push password=secret123 account 6222000000004821 done");
        log.setOccurredAt(LocalDateTime.of(2026, 9, 1, 10, 0));

        when(taskMapper.selectList(any())).thenReturn(
                List.of(connectionTask(profile.getId(), log.getTaskId())));
        Page<ConnectionOperationLog> page = new Page<>(1, 10);
        page.setTotal(1);
        page.setRecords(List.of(log));
        when(logMapper.selectPage(any(), any())).thenReturn(page);

        var result = service.logs(USER_ID, 1, 10, "cmb-001", "SUCCEEDED", "REQ-1");
        assertEquals(1, result.total());
        OperationLogResponse row = result.records().get(0);
        assertEquals("REQ-1", row.requestId());
        assertTrue(row.message().contains("password=[REDACTED]"));
        assertFalse(row.message().contains("secret123"));
        assertFalse(row.message().contains("6222000000004821"));
    }

    @Test
    void logsReturnEmptyWhenFilteredConnectionDoesNotBelongToCompany() {
        when(profileMapper.selectOne(any())).thenReturn(null);
        var result = service.logs(USER_ID, 1, 10, "unknown-conn", null, null);
        assertEquals(0, result.total());
    }

    private static com.finance.system.domain.entity.ConnectionOperationTask connectionTask(
            Long connectionId, Long taskId) {
        com.finance.system.domain.entity.ConnectionOperationTask task =
                new com.finance.system.domain.entity.ConnectionOperationTask();
        task.setId(taskId);
        task.setConnectionId(connectionId);
        return task;
    }

    private static final class StubAdapter implements BankDataAdapter {
        private final String code;
        private final BankAdapterExecutionMode mode;

        private StubAdapter(String code, BankAdapterExecutionMode mode) {
            this.code = code;
            this.mode = mode;
        }

        @Override
        public String adapterCode() {
            return code;
        }

        @Override
        public BankAdapterExecutionMode executionMode() {
            return mode;
        }

        @Override
        public BankDataCollection collect(BankDataSyncContext context) {
            return new BankDataCollection(code + "-REQ", List.of(), List.of());
        }
    }
}
