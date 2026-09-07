package com.finance.system.bankdata;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.bankdata.adapter.BankAdapterExecutionMode;
import com.finance.system.bankdata.adapter.BankDataCollection;
import com.finance.system.bankdata.adapter.BankDataSyncContext;
import com.finance.system.bankdata.dto.BankDataSyncRequest;
import com.finance.system.bankdata.dto.BankDataSyncTaskDetailResponse;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.BankDataSyncTask;
import com.finance.system.domain.entity.Company;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.BankDataSyncTaskMapper;
import com.finance.system.domain.mapper.CompanyMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Dedicated context for the account-driven scheduled scan and the bank-code adapter fallback
 * (2026-09-07). Registers a REAL-mode test double on purpose: the projection page status is a
 * deployment-wide fact ("any REAL adapter wired → REAL"), so wiring a REAL adapter inside the
 * shared V02 context would flip every projection assertion there from NOT_CONFIGURED to REAL.
 * REAL wiring therefore lives in this isolated context only.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(BankDataScheduledScanIntegrationTest.RealQaAdapterConfiguration.class)
class BankDataScheduledScanIntegrationTest {

    @Autowired
    private CompanyMapper companyMapper;
    @Autowired
    private BankAccountMapper bankAccountMapper;
    @Autowired
    private BankDataSyncTaskMapper taskMapper;
    @Autowired
    private BankDataSyncService bankDataSyncService;

    @Test
    void scheduledScanIsAccountDrivenAndDedupesPerWindow() {
        Company company = insertCompany("QA-SCAN");
        BankAccount realAccount = insertRealQaAccount(company.getId(), "QA real scheduled account");

        // Two scans in the same T-1 window: the second must reuse the first task (request-id
        // idempotency), so exactly one SCHEDULED task exists for the account.
        bankDataSyncService.triggerScheduledSyncs();
        bankDataSyncService.triggerScheduledSyncs();

        assertEquals(1, taskMapper.selectCount(new LambdaQueryWrapper<BankDataSyncTask>()
                .eq(BankDataSyncTask::getCompanyId, company.getId())
                .eq(BankDataSyncTask::getBankAccountId, realAccount.getId())
                .eq(BankDataSyncTask::getTriggerType, "SCHEDULED")));
    }

    @Test
    void manualSyncWithoutExplicitAdapterFallsBackToAccountBankCode() {
        // UI-triggered syncs send neither adapterCode nor connectionCode: resolution must fall
        // back to the account's own bank code (REAL_QA registered here) instead of failing with
        // a bare "adapter is not available" 400.
        Company company = insertCompany("QA-FB");
        BankAccount realAccount = insertRealQaAccount(company.getId(), "QA real fallback account");

        BankDataSyncTaskDetailResponse detail = bankDataSyncService.triggerForCompany(
                company.getId(), null,
                new BankDataSyncRequest(null, realAccount.getId(), null,
                        LocalDateTime.parse("2026-08-26T00:00:00"),
                        LocalDateTime.parse("2026-08-26T23:59:59")),
                "qa-bankcode-fallback-real-" + UUID.randomUUID(), "MANUAL");
        assertEquals("REAL_QA", detail.task().adapterCode());
    }

    private Company insertCompany(String prefix) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Company company = new Company();
        company.setCode(prefix + "_" + suffix);
        company.setName(prefix + " company " + suffix);
        company.setStatus("ACTIVE");
        companyMapper.insert(company);
        return company;
    }

    private BankAccount insertRealQaAccount(Long companyId, String accountName) {
        BankAccount account = new BankAccount();
        account.setCompanyId(companyId);
        account.setBankCode("REAL_QA");
        account.setAccountName(accountName);
        account.setAccountNumber("6222" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        account.setCurrency("CNY");
        account.setAvailableBalance(new BigDecimal("500000.00"));
        account.setStatus("ACTIVE");
        bankAccountMapper.insert(account);
        return account;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RealQaAdapterConfiguration {

        @Bean
        BankDataAdapter realQaAdapter() {
            // REAL-mode test double: real calls stay gated (real-adapters-enabled=false →
            // terminal UNKNOWN), which still persists the task — exactly what the assertions need.
            return new BankDataAdapter() {
                @Override
                public String adapterCode() {
                    return "REAL_QA";
                }

                @Override
                public BankAdapterExecutionMode executionMode() {
                    return BankAdapterExecutionMode.REAL;
                }

                @Override
                public BankDataCollection collect(BankDataSyncContext context) {
                    return new BankDataCollection("REAL-QA-RAW", java.util.List.of(),
                            java.util.List.of(), false, null, "SUCCESS", "SUCCESS");
                }
            };
        }
    }
}
