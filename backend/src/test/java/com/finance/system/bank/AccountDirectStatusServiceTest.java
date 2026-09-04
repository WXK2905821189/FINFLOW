package com.finance.system.bank;

import com.finance.system.bankdata.adapter.BankAdapterExecutionMode;
import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.bankdata.adapter.BankDataCollection;
import com.finance.system.bankdata.adapter.BankDataSyncContext;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.BankDataSyncTask;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.BankDataSyncTaskMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Status machine for the per-account direct-connect verdict.
 *
 * <p>The bug this guards: the account list used to render one GLOBAL "CMB is connected" flag on
 * every row, so a CITIC row (never wired) showed as connected. Status is now resolved per account
 * from two proofs - a REAL adapter is registered for the account's bank AND this very account has
 * at least one successful real sync. Simulated runs never count as evidence.
 *
 * <p>Runs against the real H2 schema with transaction rollback: the heartbeat query is a
 * MyBatis-Plus lambda wrapper and the table carries foreign keys, so stubbing the mapper would
 * test the mock instead of the SQL. Every account/task row is created inside the test and rolled
 * back afterwards.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Transactional
@TestPropertySource(properties = "bankdata.adapter.call.real-adapters-enabled=true")
@Import(AccountDirectStatusServiceTest.StubRealCmbAdapterConfiguration.class)
class AccountDirectStatusServiceTest {

    private static final long COMPANY_ID = 1L;
    private static final LocalDateTime SYNC_AT = LocalDateTime.of(2026, 9, 3, 18, 2, 10);

    @Autowired
    private AccountDirectStatusService service;

    @Autowired
    private BankAccountMapper accountMapper;

    @Autowired
    private BankDataSyncTaskMapper syncTaskMapper;

    @Test
    void notConnectedWhenNoRealAdapterIsRegisteredForTheBankCode() {
        // CITIC has no REAL-mode adapter registered in this context, so it can never be connected -
        // even though CMB is wired in the very same call.
        BankAccount citic = persistAccount("CITIC", "acct-citic-status");
        recordTask(citic, "CITIC", "SUCCEEDED", SYNC_AT);

        AccountDirectStatusService.DirectStatusView view = service.resolveOne(citic);

        assertEquals(AccountDirectStatusService.NOT_CONNECTED, view.status());
        assertNull(view.lastRealSyncAt(), "no evidence may be reported for a bank that is not wired");
    }

    @Test
    void onboardedWhenRealAdapterIsAssembledButAccountHasNoSuccessfulRealSync() {
        BankAccount cmb = persistAccount("CMB", "acct-cmb-fresh");

        AccountDirectStatusService.DirectStatusView view = service.resolveOne(cmb);

        assertEquals(AccountDirectStatusService.ONBOARDED, view.status(),
                "bank wired but no real sync yet must not be presented as connected");
        assertNull(view.lastRealSyncAt());
    }

    @Test
    void directConnectedWhenRealAdapterAndSuccessfulRealSyncExist() {
        BankAccount cmb = persistAccount("CMB", "acct-cmb-verified");
        recordTask(cmb, "CMB", "SUCCEEDED", SYNC_AT);

        AccountDirectStatusService.DirectStatusView view = service.resolveOne(cmb);

        assertEquals(AccountDirectStatusService.DIRECT_CONNECTED, view.status());
        assertEquals("2026-09-03T18:02:10", view.lastRealSyncAt());
    }

    @Test
    void mockAndFailedSyncsDoNotCountAsDirectConnectEvidence() {
        BankAccount mockOnly = persistAccount("CMB", "acct-cmb-mock-only");
        recordTask(mockOnly, "CMB_MOCK", "SUCCEEDED", SYNC_AT);
        assertEquals(AccountDirectStatusService.ONBOARDED, service.resolveOne(mockOnly).status(),
                "a simulated adapter run is not proof the bank accepted this account");

        BankAccount failed = persistAccount("CMB", "acct-cmb-failed");
        recordTask(failed, "CMB", "FAILED", SYNC_AT);
        assertEquals(AccountDirectStatusService.ONBOARDED, service.resolveOne(failed).status(),
                "a failed real sync is not proof either");
    }

    @Test
    void eachAccountIsJudgedIndependentlyWithinOneBatch() {
        BankAccount cmbVerified = persistAccount("CMB", "acct-batch-cmb-verified");
        BankAccount cmbFresh = persistAccount("CMB", "acct-batch-cmb-fresh");
        BankAccount citic = persistAccount("CITIC", "acct-batch-citic");
        recordTask(cmbVerified, "CMB", "SUCCEEDED", SYNC_AT);
        recordTask(citic, "CITIC", "SUCCEEDED", SYNC_AT);

        Map<Long, AccountDirectStatusService.DirectStatusView> views =
                service.resolve(List.of(cmbVerified, cmbFresh, citic));

        assertEquals(AccountDirectStatusService.DIRECT_CONNECTED, views.get(cmbVerified.getId()).status(),
                "CMB account with a real successful sync is connected");
        assertEquals("2026-09-03T18:02:10", views.get(cmbVerified.getId()).lastRealSyncAt());
        assertEquals(AccountDirectStatusService.ONBOARDED, views.get(cmbFresh.getId()).status(),
                "second CMB account has no sync yet - onboarded, not connected");
        assertEquals(AccountDirectStatusService.NOT_CONNECTED, views.get(citic.getId()).status(),
                "CITIC account must stay NOT_CONNECTED even while CMB is connected");
        assertNull(views.get(citic.getId()).lastRealSyncAt());
    }

    @Test
    void bankCodeMatchingIsCaseInsensitive() {
        BankAccount cmb = persistAccount("cmb", "acct-cmb-lowercase");
        recordTask(cmb, "CMB", "SUCCEEDED", SYNC_AT);

        assertEquals(AccountDirectStatusService.DIRECT_CONNECTED, service.resolveOne(cmb).status());
    }

    @Test
    void latestSuccessfulSyncWinsWhenSeveralExist() {
        BankAccount cmb = persistAccount("CMB", "acct-cmb-multi");
        recordTask(cmb, "CMB", "SUCCEEDED", SYNC_AT.minusDays(2));
        recordTask(cmb, "CMB", "SUCCEEDED", SYNC_AT);

        assertEquals("2026-09-03T18:02:10", service.resolveOne(cmb).lastRealSyncAt());
    }

    @Test
    void syncOfAnotherAccountDoesNotLeakIntoThisAccountStatus() {
        BankAccount verified = persistAccount("CMB", "acct-cmb-owner");
        BankAccount neighbour = persistAccount("CMB", "acct-cmb-neighbour");
        recordTask(verified, "CMB", "SUCCEEDED", SYNC_AT);

        assertEquals(AccountDirectStatusService.DIRECT_CONNECTED, service.resolveOne(verified).status());
        assertEquals(AccountDirectStatusService.ONBOARDED, service.resolveOne(neighbour).status(),
                "a sibling account's successful sync must not mark this account as connected");
    }

    private BankAccount persistAccount(String bankCode, String accountNumber) {
        BankAccount account = new BankAccount();
        account.setCompanyId(COMPANY_ID);
        account.setBankCode(bankCode);
        account.setAccountName("status-test " + accountNumber);
        account.setAccountNumber(accountNumber);
        account.setCurrency("CNY");
        account.setStatus("ACTIVE");
        accountMapper.insert(account);
        return account;
    }

    private void recordTask(BankAccount account, String adapterCode, String status, LocalDateTime completedAt) {
        BankDataSyncTask task = new BankDataSyncTask();
        task.setCompanyId(COMPANY_ID);
        task.setTaskNo(UUID.randomUUID().toString().substring(0, 32));
        task.setAdapterCode(adapterCode);
        task.setBankAccountId(account.getId());
        task.setRequestId(UUID.randomUUID().toString().substring(0, 32));
        task.setStatus(status);
        task.setRawCount(0);
        task.setNormalizedCount(0);
        task.setDuplicateCount(0);
        task.setInvalidCount(0);
        task.setStartedAt(completedAt);
        task.setCompletedAt(completedAt);
        syncTaskMapper.insert(task);
    }

    /** Registers a deterministic REAL-mode CMB adapter so "bank wired" is true for CMB only. */
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
