package com.finance.system.bankdata.aggregation;

import com.finance.system.bank.dto.BankConnectionTestResponse;
import com.finance.system.bankdata.adapter.BankAdapterExecutionMode;
import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.bankdata.adapter.BankDataBalanceEntry;
import com.finance.system.bankdata.adapter.BankDataCollection;
import com.finance.system.bankdata.adapter.BankDataSyncContext;
import com.finance.system.bankdata.adapter.BankExchangeEvidence;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.common.tenant.CompanyScopeService;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.Company;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.CompanyMapper;
import com.finance.system.rbac.RbacService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the read-only bank connectivity probe. The executor is real (default
 * properties: real adapters disabled) so rate/timeout semantics stay production-shaped;
 * adapters are fakes, no Spring context and no vendor SDK is touched.
 */
class BankConnectionTestServiceTest {

    private static final Long USER_ID = 42L;
    private static final Long ACCOUNT_ID = 7L;
    private static final Long COMPANY_ID = 3L;

    private BankAccountMapper bankAccountMapper;
    private CompanyMapper companyMapper;
    private CompanyScopeService companyScope;
    private RbacService rbacService;
    private BankAdapterCallProperties callProperties;
    private BankAccount account;

    @BeforeEach
    void setUp() {
        bankAccountMapper = mock(BankAccountMapper.class);
        companyMapper = mock(CompanyMapper.class);
        companyScope = mock(CompanyScopeService.class);
        rbacService = mock(RbacService.class);
        callProperties = new BankAdapterCallProperties();
        account = new BankAccount();
        account.setId(ACCOUNT_ID);
        account.setCompanyId(COMPANY_ID);
        account.setBankCode("CITIC");
        account.setAccountNumber("1234567890");
        when(rbacService.permissionCodesForUser(USER_ID)).thenReturn(List.of());
        when(companyScope.companyIdForUser(USER_ID)).thenReturn(COMPANY_ID);
        when(bankAccountMapper.selectOne(any())).thenReturn(account);
    }

    private BankConnectionTestService service(BankDataAdapter adapter) {
        BankAdapterCallExecutor executor = new BankAdapterCallExecutor(callProperties,
                Executors.newFixedThreadPool(1), Clock.systemUTC());
        return new BankConnectionTestService(bankAccountMapper, companyMapper, companyScope,
                rbacService, new BankDataAdapterRegistry(List.of(adapter)), executor);
    }

    private BankDataAdapter fakeAdapter(BankAdapterExecutionMode mode, BankDataCollection collection) {
        return new BankDataAdapter() {
            @Override
            public String adapterCode() {
                return "CITIC";
            }

            @Override
            public BankDataCollection collect(BankDataSyncContext context) {
                return collection;
            }

            @Override
            public BankAdapterExecutionMode executionMode() {
                return mode;
            }
        };
    }

    private BankDataCollection successCollection() {
        BankExchangeEvidence evidence = new BankExchangeEvidence("https://bank.example/gateway",
                "DLTRNALL", null, null, 350L, 200, null);
        BankDataBalanceEntry balance = new BankDataBalanceEntry("BR-1", ACCOUNT_ID,
                new BigDecimal("12345.67"), "CNY", LocalDateTime.now());
        return new BankDataCollection("BR-1", List.of(), List.of(balance), false, null,
                "AAAAAAA", "AAAAAAA", null, evidence);
    }

    @Test
    void simulatedAdapterAnswersSuccessfully() {
        BankConnectionTestResponse response = service(
                fakeAdapter(BankAdapterExecutionMode.SIMULATED, successCollection()))
                .test(USER_ID, ACCOUNT_ID);

        assertEquals(BankConnectionTestService.CONNECTED, response.result());
        assertEquals(0, response.availableBalance().compareTo(new BigDecimal("12345.67")));
        assertEquals("CNY", response.currency());
        assertEquals("DLTRNALL", response.operation());
        assertEquals(350L, response.durationMs());
        assertEquals("https://bank.example/gateway", response.endpoint());
        assertEquals(0, response.statementRows());
    }

    @Test
    void realAdapterWithSwitchOffReportsDisabled() {
        BankConnectionTestResponse response = service(
                fakeAdapter(BankAdapterExecutionMode.REAL, successCollection()))
                .test(USER_ID, ACCOUNT_ID);

        assertEquals(BankConnectionTestService.DISABLED, response.result());
        assertTrue(response.message().toLowerCase().contains("disabled"));
    }

    @Test
    void realAdapterWithSwitchOnReachesFakeBank() {
        callProperties.setRealAdaptersEnabled(true);
        BankConnectionTestResponse response = service(
                fakeAdapter(BankAdapterExecutionMode.REAL, successCollection()))
                .test(USER_ID, ACCOUNT_ID);

        assertEquals(BankConnectionTestService.CONNECTED, response.result());
        assertEquals("BR-1", response.bankRequestNo());
    }

    @Test
    void bankFailureCodeMapsToFailed() {
        BankDataCollection failed = new BankDataCollection("BR-2", List.of(), List.of(), false, null,
                "EEEEEEE", "EEEEEEE", null, null);
        BankConnectionTestResponse response = service(
                fakeAdapter(BankAdapterExecutionMode.SIMULATED, failed))
                .test(USER_ID, ACCOUNT_ID);

        assertEquals(BankConnectionTestService.FAILED, response.result());
        assertTrue(response.message().contains("EEEEEEE"));
    }

    @Test
    void unknownAccountIsRejected() {
        when(bankAccountMapper.selectOne(any())).thenReturn(null);
        BusinessException thrown = assertThrows(BusinessException.class, () -> service(
                fakeAdapter(BankAdapterExecutionMode.SIMULATED, successCollection()))
                .test(USER_ID, ACCOUNT_ID));
        assertTrue(thrown.getMessage().contains("not found"));
    }

    @Test
    void unregisteredBankCodeReportsDisabledInsteadOfFailing() {
        account.setBankCode("ICBC");

        BankConnectionTestResponse response = service(
                fakeAdapter(BankAdapterExecutionMode.SIMULATED, successCollection()))
                .test(USER_ID, ACCOUNT_ID);

        // Deployment state ("this bank is not wired here"), not a bad request: the probe must
        // report it. 400 would surface as a red "请求未能完成" dialog the operator cannot act on.
        assertEquals(BankConnectionTestService.DISABLED, response.result());
        assertTrue(response.message().contains("ICBC"));
        assertNull(response.bankRequestNo());
        assertNull(response.endpoint());
    }

    @Test
    void crossCompanyHolderReachesOtherCompanyAccounts() {
        when(rbacService.permissionCodesForUser(USER_ID)).thenReturn(List.of("bankdata:cross-company:view"));
        Company other = mock(Company.class);
        when(other.getId()).thenReturn(99L);
        when(companyMapper.selectList(any())).thenReturn(List.of(other));
        account.setCompanyId(99L);

        BankConnectionTestResponse response = service(
                fakeAdapter(BankAdapterExecutionMode.SIMULATED, successCollection()))
                .test(USER_ID, ACCOUNT_ID);

        assertEquals(BankConnectionTestService.CONNECTED, response.result());
    }
}
