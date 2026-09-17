package com.finance.system.statement.voucherrule;

import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.Company;
import com.finance.system.domain.entity.StatementRecord;
import com.finance.system.statement.kingdee.KingdeeProperties;
import com.finance.system.statement.voucherrule.dto.KingdeeVoucherEntryDraft;
import com.finance.system.statement.voucherrule.dto.KingdeeVoucherRulePreview;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则引擎匹配服务（WP-B）：全部断言直接锚定 V34 seed 原文（22 条财务映射规则）。
 * 纯内存构造流水/账户/主体，不落库——匹配是确定性计算，与共享 H2 数据无关。
 */
@SpringBootTest
@ActiveProfiles("dev")
class KingdeeVoucherMatchingServiceTest {

    @Autowired
    private KingdeeVoucherMatchingService matchingService;

    @Autowired
    private KingdeeProperties kingdeeProps;

    // ---- seed rule 6: 支付银行手续费（300,400 / CITIC / EXPENSE / 摘要含手续费等） ----

    @Test
    void bankFeeStatementAutoFillsRule6() {
        StatementRecord statement = statement("北京雪云锐创科技有限公司", "CITIC", "EXPENSE",
                new BigDecimal("345.67"), "银行手续费", "某某收款方");
        KingdeeVoucherRulePreview preview = matchingService.preview(statement,
                account("CITIC"), company("北京雪云锐创科技有限公司"));

        assertEquals(KingdeeVoucherMatchingService.ST_AUTO_FILL, preview.status());
        assertEquals(1, preview.candidates().size());
        KingdeeVoucherRulePreview.Candidate candidate = preview.candidates().get(0);
        assertEquals(6, candidate.ruleNo());
        assertFalse(candidate.needManualAmount());
        // 借 6603.04 财务费用_手续费（NONE 维度）= 全额；贷 1002 银行存款（BANK_ACCOUNT 维度）
        KingdeeVoucherEntryDraft debit = candidate.debitLines().get(0);
        assertEquals("6603.04", debit.account());
        assertEquals("DEBIT", debit.side());
        assertEquals(new BigDecimal("345.67"), debit.amount());
        assertEquals("FULL", debit.share());
        assertNull(debit.dimensionValue());
        KingdeeVoucherEntryDraft credit = candidate.creditLines().get(0);
        assertEquals("1002", credit.account());
        assertEquals("BANK_ACCOUNT", credit.dimension());
        assertEquals(new BigDecimal("345.67"), credit.amount());
    }

    // ---- 预过滤：渠道 / 主体 / 方向 ----

    @Test
    void channelScopeFiltersOutNonMatchingBank() {
        // 规则 6 仅 CITIC；CMB 账户不命中，且该流水不撞任何其他规则 → UNMATCHED
        StatementRecord statement = statement("北京雪云锐创科技有限公司", "CMB", "EXPENSE",
                new BigDecimal("345.67"), "银行手续费", "某某收款方");
        KingdeeVoucherRulePreview preview = matchingService.preview(statement,
                account("CMB"), company("北京雪云锐创科技有限公司"));
        assertEquals(KingdeeVoucherMatchingService.ST_UNMATCHED, preview.status());
    }

    @Test
    void orgScopeFiltersOutOtherCompanyRules() {
        // 规则 1/4/5/13 等 300 独占规则，对雪云(400)流水不开放；摘要「报销」+公司对手方
        // 使规则 1 的 EMPLOYEE_NAME 也不命中 → UNMATCHED
        StatementRecord statement = statement("北京雪云锐创科技有限公司", "CITIC", "EXPENSE",
                new BigDecimal("888.00"), "报销", "腾讯云计算（北京）有限责任公司");
        KingdeeVoucherRulePreview preview = matchingService.preview(statement,
                account("CITIC"), company("北京雪云锐创科技有限公司"));
        assertEquals(KingdeeVoucherMatchingService.ST_UNMATCHED, preview.status());
    }

    @Test
    void directionFilterKeepsIncomeRulesAwayFromExpense() {
        // 收入宽泛兜底（规则 21，关键词 收入/转入/打款）不允许命中支出流水
        StatementRecord statement = statement("北京雪云锐创科技有限公司", "CITIC", "EXPENSE",
                new BigDecimal("99.00"), "打款", "某某收款方");
        KingdeeVoucherRulePreview preview = matchingService.preview(statement,
                account("CITIC"), company("北京雪云锐创科技有限公司"));
        assertEquals(KingdeeVoucherMatchingService.ST_UNMATCHED, preview.status());
    }

    // ---- 多命中 → 人工确认队列（金额门的自然实现） ----

    @Test
    void socialInsuranceAndTaxBothMatchAndStayManual() {
        // 规则 10(社保,金额大,300/400/900)/11(个税,金额小,300) 同附言「扣国地税」：
        // 即设(300)主体两条都命中——财务给阈值前恒多命中，全部进人工确认队列
        // 注意：公司主体由 preview 的 company 参数决定（statement helper 的首参仅文档性）
        StatementRecord statement = statement("北京即设科技有限公司", "CITIC", "EXPENSE",
                new BigDecimal("5600.00"), "扣国地税", "北京市税务局");
        KingdeeVoucherRulePreview preview = matchingService.preview(statement,
                account("CITIC"), company("北京即设科技有限公司"));
        assertEquals(KingdeeVoucherMatchingService.ST_CANDIDATES, preview.status());
        assertEquals(2, preview.candidates().size());
        // 金额门=多候选恒人工选择；社保候选(规则10)自带 MANUAL 拆分行
        assertTrue(preview.candidates().stream().anyMatch(c -> c.ruleNo() == 10 && c.needManualAmount()));
        assertTrue(preview.candidates().stream().anyMatch(c -> c.ruleNo() == 11));
    }

    // ---- EMPLOYEE_NAME 启发式 + 第二张凭证 ----

    @Test
    void employeeNameExpenseMatchesRule1WithExtraVoucher() {
        StatementRecord statement = statement("北京即设科技有限公司", "CITIC", "EXPENSE",
                new BigDecimal("260.00"), "员工报销差旅费", "张三");
        KingdeeVoucherRulePreview preview = matchingService.preview(statement,
                account("CITIC"), company("北京即设科技有限公司"));
        assertEquals(KingdeeVoucherMatchingService.ST_AUTO_FILL, preview.status());
        KingdeeVoucherRulePreview.Candidate candidate = preview.candidates().get(0);
        assertEquals(1, candidate.ruleNo());
        assertNotNullExtra(candidate);
        // 第二张凭证：借 6602.11 管理费用_福利费 / 贷 2241.04（EMPLOYEE 维度=对手方个人名）
        assertEquals("EMPLOYEE", candidate.extraVoucher().creditLines().get(0).dimension());
        assertEquals("张三", candidate.extraVoucher().creditLines().get(0).dimensionValue());
    }

    private static void assertNotNullExtra(KingdeeVoucherRulePreview.Candidate candidate) {
        assertTrue(candidate.extraVoucher() != null
                        && candidate.extraVoucher().debitLines().get(0).account().equals("6602.11"),
                "规则 1 必须带「直接确认费用」第二张凭证模板");
    }

    // ---- IN_ORG_LIST：集团内部主体往来 ----

    @Test
    void groupInternalCounterpartyMatchesRule2() {
        StatementRecord statement = statement("北京即设科技有限公司", "CITIC", "EXPENSE",
                new BigDecimal("12000.00"), "往来款划转", "北京即设科技有限公司");
        KingdeeVoucherRulePreview preview = matchingService.preview(statement,
                account("CITIC"), company("北京即设科技有限公司"));
        assertEquals(KingdeeVoucherMatchingService.ST_AUTO_FILL, preview.status());
        assertEquals(2, preview.candidates().get(0).ruleNo());
        assertEquals("2241.05", preview.candidates().get(0).debitLines().get(0).account());
        assertEquals("300", preview.candidates().get(0).debitLines().get(0).dimensionValue(),
                "ORG 维度解析为金蝶组织编码（即设=300）");
    }

    // ---- EQUAL 分摊 + 尾差吸收 ----

    @Test
    void housingFundSplitsEquallyWithRoundingRemainder() {
        StatementRecord statement = statement("北京即设科技有限公司", "CMB", "EXPENSE",
                new BigDecimal("100.01"), "缴纳公积金", "北京住房公积金管理中心");
        KingdeeVoucherRulePreview preview = matchingService.preview(statement,
                account("CMB"), company("北京即设科技有限公司"));
        assertEquals(KingdeeVoucherMatchingService.ST_AUTO_FILL, preview.status());
        List<KingdeeVoucherEntryDraft> debits = preview.candidates().get(0).debitLines();
        assertEquals(2, debits.size());
        assertEquals(new BigDecimal("50.01"), debits.get(0).amount(),
                "100.01/2 四舍五入 = 50.01（HALF_UP）");
        assertEquals(new BigDecimal("50.00"), debits.get(1).amount(), "尾差由末行吸收");
        assertEquals("2211.03", debits.get(0).account());
        assertEquals("2241.03", debits.get(1).account());
    }

    // ---- BY_SUMMARY_BRANCH 维度 ----

    @Test
    void tencentCloudSummaryBranchResolvesSupplier() {
        // seed 真实重叠：摘要「云服务费」含「服务费」→ 规则 14（宽泛费用）与规则 18（腾讯云精确）
        // 同优先级 20 双命中 → 按设计进人工确认队列；断言聚焦规则 18 的摘要分支维度解析
        StatementRecord statement = statement("北京雪云锐创科技有限公司", "CITIC", "EXPENSE",
                new BigDecimal("999.00"), "腾讯云服务费-图虫账号", "腾讯云计算（北京）有限责任公司");
        KingdeeVoucherRulePreview preview = matchingService.preview(statement,
                account("CITIC"), company("北京雪云锐创科技有限公司"));
        assertEquals(KingdeeVoucherMatchingService.ST_CANDIDATES, preview.status());
        KingdeeVoucherRulePreview.Candidate tencent = preview.candidates().stream()
                .filter(c -> c.ruleNo() == 18).findFirst().orElseThrow();
        KingdeeVoucherEntryDraft debit = tencent.debitLines().get(0);
        assertEquals("BY_SUMMARY_BRANCH", debit.dimension());
        assertEquals("腾讯云计算（北京）有限责任公司-图虫", debit.dimensionValue());
    }

    // ---- NOT_ELIGIBLE ----

    @Test
    void missingAmountIsNotEligible() {
        StatementRecord statement = statement("北京雪云锐创科技有限公司", "CITIC", "EXPENSE",
                null, "银行手续费", "某某收款方");
        KingdeeVoucherRulePreview preview = matchingService.preview(statement,
                account("CITIC"), company("北京雪云锐创科技有限公司"));
        assertEquals(KingdeeVoucherMatchingService.ST_NOT_ELIGIBLE, preview.status());
    }

    // ---- 个人姓名启发式 ----

    @Test
    void personalNameHeuristic() {
        assertTrue(KingdeeVoucherMatchingService.isPersonalName("张三"));
        assertTrue(KingdeeVoucherMatchingService.isPersonalName("王小棵"));
        assertTrue(KingdeeVoucherMatchingService.isPersonalName("阿依·古丽"));
        assertFalse(KingdeeVoucherMatchingService.isPersonalName("腾讯云计算（北京）有限责任公司"));
        assertFalse(KingdeeVoucherMatchingService.isPersonalName("招商银行"));
        assertFalse(KingdeeVoucherMatchingService.isPersonalName("A123456789"));
        assertFalse(KingdeeVoucherMatchingService.isPersonalName(""));
        assertFalse(KingdeeVoucherMatchingService.isPersonalName(null));
    }

    // ---- fixture helpers（纯内存，不入库） ----

    private static StatementRecord statement(String companyName, String bankCode, String direction,
                                             BigDecimal amount, String summary, String counterparty) {
        StatementRecord statement = new StatementRecord();
        statement.setId(System.nanoTime()); // preview 只读 id，无入库
        statement.setStatementNo("KVR-TEST-" + Long.toHexString(System.nanoTime()));
        statement.setCompanyId(1L);
        statement.setBankAccountId(1L);
        statement.setTransactionTime(LocalDateTime.parse("2026-09-16T10:15:00"));
        statement.setDirection(direction);
        statement.setAmount(amount);
        statement.setCurrency("CNY");
        statement.setCounterpartyName(counterparty);
        statement.setSummary(summary);
        statement.setReviewStatus("APPROVED");
        return statement;
    }

    private static BankAccount account(String bankCode) {
        BankAccount account = new BankAccount();
        account.setBankCode(bankCode);
        return account;
    }

    private static Company company(String name) {
        Company company = new Company();
        company.setName(name);
        return company;
    }
}
