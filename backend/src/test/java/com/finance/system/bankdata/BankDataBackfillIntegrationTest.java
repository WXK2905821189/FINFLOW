package com.finance.system.bankdata;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.bankdata.adapter.BankAdapterExecutionMode;
import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.bankdata.adapter.BankDataBalanceEntry;
import com.finance.system.bankdata.adapter.BankDataCollection;
import com.finance.system.bankdata.adapter.BankDataEntry;
import com.finance.system.bankdata.adapter.BankDataSyncContext;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.BankDataBalance;
import com.finance.system.domain.entity.BankDataStatement;
import com.finance.system.domain.entity.BankDataSyncTask;
import com.finance.system.domain.entity.Company;
import com.finance.system.domain.entity.SysUser;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.BankDataBalanceMapper;
import com.finance.system.domain.mapper.BankDataStatementMapper;
import com.finance.system.domain.mapper.BankDataSyncTaskMapper;
import com.finance.system.domain.mapper.CompanyMapper;
import com.finance.system.domain.mapper.SysUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
/**
 * W17 包 F 集成测试：历史数据回补编排层 + 历史余额落库。
 *
 * <p>上下文注册 BF_QA 模拟 adapter（SIMULATED，collect 与 collectHistoryBalance 都有确定性
 * 返回）；流水片走既有 triggerForCompany BACKFILL 管道，余额片走编排层直落库（balanceKey
 * 与 BankDataSyncExecutor 同口径）。真实银行调用不在此覆盖，放部署收口后小区间先行。</p>
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(BankDataBackfillIntegrationTest.BackfillQaAdapterConfiguration.class)
class BankDataBackfillIntegrationTest {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    @Autowired
    private CompanyMapper companyMapper;
    @Autowired
    private BankAccountMapper bankAccountMapper;
    @Autowired
    private BankDataSyncTaskMapper taskMapper;
    @Autowired
    private BankDataStatementMapper statementMapper;
    @Autowired
    private BankDataBalanceMapper balanceMapper;
    @Autowired
    private SysUserMapper userMapper;
    @Autowired
    private BankDataBackfillService backfillService;
    @Autowired
    private BankDataSyncService bankDataSyncService;

    @Test
    void statementChunksAreSplitAt90DaysAndEachChunkGetsItsOwnTask() {
        Company company = insertCompany("QA-BF1");
        BankAccount account = insertQaAccount(company.getId(), "QA backfill 91-day account");
        Long userId = insertUser(company.getId(), "qa-bf1-admin");

        // 92 天区间（含两端）→ gap 91 > 90 → 两片（90 + 1），与中信 92 天硬校验留余量。
        LocalDate start = LocalDate.parse("2026-03-01");
        LocalDate end = start.plusDays(91);
        assertEquals(92, java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1);
        BankDataBackfillService.BackfillSummary summary = backfillService.runBackfill(
                company.getId(), userId, List.of(account),
                new BankDataBackfillService.BackfillRequest(account.getId(), start, end, true, false));

        assertEquals(2, summary.statementChunks());
        assertEquals(0, summary.statementChunkFailures());
        assertEquals(2, taskMapper.selectCount(new LambdaQueryWrapper<BankDataSyncTask>()
                .eq(BankDataSyncTask::getCompanyId, company.getId())
                .eq(BankDataSyncTask::getTriggerType, "BACKFILL")));
    }

    @Test
    void chunkFailureDoesNotStopLaterChunks() {
        Company company = insertCompany("QA-BF2");
        BankAccount account = insertQaAccount(company.getId(), "QA backfill failfast account");
        Long userId = insertUser(company.getId(), "qa-bf2-admin");

        // 区间 3 天 = 1 个 90 天片：让该片内每一天命中 stub 失败码 → 任务 FAILED；
        // 断言单片失败只计入汇总（不抛异常中断编排），失败任务本身也落了库（BACKFILL 可见）。
        LocalDate start = LocalDate.parse("2026-06-01");
        LocalDate end = LocalDate.parse("2026-06-03");
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            BackfillQaAdapter.failDays.add(day);
        }

        BankDataBackfillService.BackfillSummary summary = backfillService.runBackfill(
                company.getId(), userId, List.of(account),
                new BankDataBackfillService.BackfillRequest(account.getId(), start, end, true, false));

        // 单片失败不中断：汇总记 1 片失败；失败任务本身落库（BACKFILL + FAILED 可见）。
        assertEquals(1, summary.statementChunks());
        assertEquals(1, summary.statementChunkFailures());
        // 失败任务本身也落了库（BACKFILL 记录可见，状态 FAILED）。
        assertEquals(1, taskMapper.selectCount(new LambdaQueryWrapper<BankDataSyncTask>()
                .eq(BankDataSyncTask::getCompanyId, company.getId())
                .eq(BankDataSyncTask::getTriggerType, "BACKFILL")
                .eq(BankDataSyncTask::getStatus, "FAILED")));
    }

    @Test
    void backfillRequestIdIsIdempotentAcrossReplays() {
        Company company = insertCompany("QA-BF3");
        BankAccount account = insertQaAccount(company.getId(), "QA backfill idempotent account");
        Long userId = insertUser(company.getId(), "qa-bf3-admin");

        LocalDate start = LocalDate.parse("2026-05-01");
        LocalDate end = LocalDate.parse("2026-05-02");
        BankDataBackfillService.BackfillSummary first = backfillService.runBackfill(
                company.getId(), userId, List.of(account),
                new BankDataBackfillService.BackfillRequest(account.getId(), start, end, true, false));
        BankDataBackfillService.BackfillSummary second = backfillService.runBackfill(
                company.getId(), userId, List.of(account),
                new BankDataBackfillService.BackfillRequest(account.getId(), start, end, true, false));

        assertEquals(1, first.statementChunks());
        assertEquals(1, second.statementChunks());
        // 重放不新建任务：BACKFILL 任务数不变（triggerForCompany requestId 幂等复用）。
        assertEquals(1, taskMapper.selectCount(new LambdaQueryWrapper<BankDataSyncTask>()
                .eq(BankDataSyncTask::getCompanyId, company.getId())
                .eq(BankDataSyncTask::getTriggerType, "BACKFILL")));
    }

    @Test
    void historicalBalancesLandWithHistoricalAsOfTimeAndStayIdempotent() {
        Company company = insertCompany("QA-BF4");
        BankAccount account = insertQaAccount(company.getId(), "QA backfill balance account");
        Long userId = insertUser(company.getId(), "qa-bf4-admin");

        // 63 天区间（含两端，gap 62）→ 余额切片 ≤30 天（gap）→ 3 片（31+31+1 天），63 条快照。
        LocalDate start = LocalDate.parse("2026-04-01");
        LocalDate end = LocalDate.parse("2026-06-02");
        BankDataBackfillService.BackfillSummary summary = backfillService.runBackfill(
                company.getId(), userId, List.of(account),
                new BankDataBackfillService.BackfillRequest(account.getId(), start, end, false, true));

        assertEquals(3, summary.balanceChunks());
        assertEquals(0, summary.balanceChunkFailures());

        List<BankDataBalance> rows = balanceMapper.selectList(new LambdaQueryWrapper<BankDataBalance>()
                .eq(BankDataBalance::getCompanyId, company.getId())
                .eq(BankDataBalance::getBankAccountId, account.getId())
                .orderByAsc(BankDataBalance::getAsOfTime));
        assertEquals(63, rows.size(), "63-day inclusive range = 63 daily snapshots");
        // asOfTime 必须是历史日期（每天 00:00），不是抓取时刻。
        assertEquals(start.atStartOfDay(), rows.get(0).getAsOfTime());
        assertEquals(end.atStartOfDay(), rows.get(rows.size() - 1).getAsOfTime());

        // 同区间重跑 → balanceKey（accountId|asOfTime）去重，无新增行。
        backfillService.runBackfill(company.getId(), userId, List.of(account),
                new BankDataBackfillService.BackfillRequest(account.getId(), start, end, false, true));
        assertEquals(63, balanceMapper.selectCount(new LambdaQueryWrapper<BankDataBalance>()
                .eq(BankDataBalance::getCompanyId, company.getId())
                .eq(BankDataBalance::getBankAccountId, account.getId())),
                "replay must not create duplicate balance rows");
    }

    @Test
    void overlappingRealtimeAndBackfillWindowsDoNotDuplicateRows() {
        Company company = insertCompany("QA-BF5");
        BankAccount account = insertQaAccount(company.getId(), "QA backfill overlap account");
        Long userId = insertUser(company.getId(), "qa-bf5-admin");

        // 同一天：先实时同步（MANUAL 管道），再回补同窗口 → 流水 statementNo 幂等 + 余额
        // asOfTime 去重，无重复行。
        LocalDate day = LocalDate.parse("2026-07-01");
        bankDataSyncService.triggerForCompany(company.getId(), userId,
                new com.finance.system.bankdata.dto.BankDataSyncRequest(null, account.getId(), null,
                        day.atStartOfDay(), day.plusDays(1).atStartOfDay().minusNanos(1)),
                "bf-overlap-real-" + UUID.randomUUID(), "MANUAL");
        backfillService.runBackfill(company.getId(), userId, List.of(account),
                new BankDataBackfillService.BackfillRequest(account.getId(), day, day, true, false));

        long statements = statementMapper.selectCount(new LambdaQueryWrapper<BankDataStatement>()
                .eq(BankDataStatement::getCompanyId, company.getId())
                .eq(BankDataStatement::getBankAccountId, account.getId()));
        assertEquals(1, statements, "overlap window must not duplicate statement rows");
        long balances = balanceMapper.selectCount(new LambdaQueryWrapper<BankDataBalance>()
                .eq(BankDataBalance::getCompanyId, company.getId())
                .eq(BankDataBalance::getBankAccountId, account.getId()));
        assertEquals(1, balances, "overlap window must not duplicate balance rows");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private Company insertCompany(String prefix) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Company company = new Company();
        company.setCode(prefix + "_" + suffix);
        company.setName(prefix + " company " + suffix);
        company.setStatus("ACTIVE");
        companyMapper.insert(company);
        return company;
    }

    private BankAccount insertQaAccount(Long companyId, String accountName) {
        BankAccount account = new BankAccount();
        account.setCompanyId(companyId);
        account.setBankCode("BF_QA");
        account.setAccountName(accountName);
        account.setAccountNumber("6222" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        account.setCurrency("CNY");
        account.setAvailableBalance(new BigDecimal("500000.00"));
        account.setStatus("ACTIVE");
        bankAccountMapper.insert(account);
        return account;
    }

    private Long insertUser(Long companyId, String username) {
        SysUser user = new SysUser();
        user.setCompanyId(companyId);
        user.setUsername(username + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        user.setPasswordHash("$2a$10$qa-test-only-not-a-real-hash-not-used-for-login-in-this-class");
        user.setEmail(username + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + "@qa.finflow.local");
        user.setStatus("ACTIVE");
        userMapper.insert(user);
        return user.getId();
    }

    /**
     * BF_QA 模拟 adapter：collect 每窗口日 1 条流水 + 1 条实时余额；collectHistoryBalance
     * 每窗口日 1 条按日快照（asOfTime=当日 00:00）。命中 failWindows 的窗口返回失败码。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class BackfillQaAdapterConfiguration {

        @Bean
        BankDataAdapter backfillQaAdapter() {
            return new BackfillQaAdapter();
        }
    }

    static class BackfillQaAdapter implements BankDataAdapter {

        /** 命中日期的窗口返回失败码（executor 按天切窗后逐窗判定）。 */
        static final java.util.Set<LocalDate> failDays = java.util.concurrent.ConcurrentHashMap.newKeySet();
        static final AtomicInteger CALLS = new AtomicInteger();

        @Override
        public String adapterCode() {
            return "BF_QA";
        }

        @Override
        public BankAdapterExecutionMode executionMode() {
            return BankAdapterExecutionMode.SIMULATED;
        }

        @Override
        public BankDataCollection collect(BankDataSyncContext context) {
            CALLS.incrementAndGet();
            if (failDays.contains(context.windowStart().toLocalDate())) {
                return new BankDataCollection("BF-QA-FAIL", List.of(), List.of(), false, null,
                        "EEEEEEE", "FAILED");
            }
            String day = context.windowStart().toLocalDate().format(DAY);
            BankDataEntry entry = new BankDataEntry("BF-QA-REQ-" + day,
                    "BF-QA-STMT-" + day + "-001", context.bankAccountId(),
                    context.windowStart().plusHours(2), "INCOME", new BigDecimal("100.00"),
                    "CNY", "回补测试对手方", "6222000000000000", "backfill qa statement");
            BankDataBalanceEntry balance = new BankDataBalanceEntry("BF-QA-REQ-" + day,
                    context.bankAccountId(), new BigDecimal("900000.00"), "CNY",
                    context.windowEnd().minusMinutes(1));
            return new BankDataCollection("BF-QA-REQ-" + day, List.of(entry), List.of(balance),
                    false, null, "SUCCESS", "SUCCESS");
        }

        @Override
        public BankDataCollection collectHistoryBalance(BankDataSyncContext context) {
            List<BankDataBalanceEntry> balances = new java.util.ArrayList<>();
            LocalDate cursor = context.windowStart().toLocalDate();
            LocalDate end = context.windowEnd().toLocalDate();
            while (!cursor.isAfter(end)) {
                balances.add(new BankDataBalanceEntry("BF-QA-HBAL-" + cursor.format(DAY),
                        context.bankAccountId(), new BigDecimal("800000.00"), "CNY",
                        cursor.atStartOfDay()));
                cursor = cursor.plusDays(1);
            }
            return new BankDataCollection("BF-QA-HBAL-" + context.windowStart().toLocalDate().format(DAY),
                    List.of(), List.copyOf(balances), false, null, "SUCCESS", "SUCCESS");
        }
    }
}
