package com.finance.system.statement.voucherrule;

import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.Company;
import com.finance.system.domain.entity.StatementRecord;
import com.finance.system.statement.voucherrule.dto.KingdeeDimensionDtos.ResolvedDimension;
import com.finance.system.statement.voucherrule.dto.KingdeeVoucherEntryDraft;
import com.finance.system.statement.voucherrule.dto.KingdeeVoucherRulePreview;
import com.finance.system.statement.voucherrule.dto.KingdeeVoucherRuleResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 匹配服务维度解析（V42，2026-09-21）：多维度声明、档案编码翻译、图虫系主体解析。
 *
 * <p>纯单测（mock 规则服务与维度服务），不依赖 DB——断言锚在「规则声明 → 分录草稿」的转换上。</p>
 */
class KingdeeVoucherMatchingDimensionsTest {

    private final KingdeeVoucherRuleService ruleService = mock(KingdeeVoucherRuleService.class);
    private final KingdeeDimensionMappingService dimensionService =
            mock(KingdeeDimensionMappingService.class);
    /** 真实组织解析器：顺带覆盖图虫系 4 个主体的关键词映射与顺序敏感。 */
    private final KingdeeVoucherMatchingService service = new KingdeeVoucherMatchingService(
            ruleService, new KingdeeOrgResolver(), dimensionService);

    // ---------------- 多维度 ----------------

    @Test
    void multiDimensionsAreResolvedToKingdeeCodes() {
        when(ruleService.listRules(true)).thenReturn(List.of(rule(List.of(
                new KingdeeVoucherRuleResponse.LineTemplate("6602.11", "管理费用_福利费", null, null, null,
                        "FULL", List.of(
                        new KingdeeVoucherRuleResponse.DimensionSpec("SUPPLIER", "NAME", null),
                        new KingdeeVoucherRuleResponse.DimensionSpec("BUSINESS_LINE", "SUMMARY", null)))), List.of())));
        when(dimensionService.resolve(eq("SUPPLIER"), anyString(), any()))
                .thenReturn(new ResolvedDimension("SUPPLIER", "FF100004", "VEN00511", null));
        when(dimensionService.resolve(eq("BUSINESS_LINE"), anyString(), any()))
                .thenReturn(new ResolvedDimension("BUSINESS_LINE", "FF100008", "YX001", null));

        KingdeeVoucherEntryDraft draft = firstDebit("服务费-图虫业务线", "某某供应商");

        assertEquals(2, draft.extraDimensions().size());
        assertEquals("VEN00511", draft.extraDimensions().get(0).value());
        assertTrue(draft.extraDimensions().get(0).injectable());
        assertEquals("FF100008", draft.extraDimensions().get(1).slot());
    }

    @Test
    void unresolvedDimensionKeepsNoteForPushTimeRejection() {
        when(ruleService.listRules(true)).thenReturn(List.of(rule(List.of(
                new KingdeeVoucherRuleResponse.LineTemplate("6602.11", "管理费用", null, null, null,
                        "FULL", List.of(
                        new KingdeeVoucherRuleResponse.DimensionSpec("SUPPLIER", "NAME", null)))), List.of())));
        when(dimensionService.resolve(eq("SUPPLIER"), anyString(), any()))
                .thenReturn(new ResolvedDimension("SUPPLIER", null, null,
                        "维度 SUPPLIER 未配置弹性域槽位（在「维度映射 › 槽位配置」补）"));

        KingdeeVoucherEntryDraft draft = firstDebit("服务费", "新供应商");

        // 解析不出来也保留声明：payload builder 会在推送前拒绝并透出这条原因（不静默少维度）
        assertEquals(1, draft.extraDimensions().size());
        assertNull(draft.extraDimensions().get(0).value());
        assertEquals(false, draft.extraDimensions().get(0).injectable());
        assertTrue(draft.extraDimensions().get(0).note().contains("槽位配置"));
    }

    @Test
    void bankAccountDimensionInMultiListUsesAccountLevelMapping() {
        when(ruleService.listRules(true)).thenReturn(List.of(rule(List.of(
                new KingdeeVoucherRuleResponse.LineTemplate("6602.11", "管理费用", null, null, null,
                        "FULL", List.of(
                        new KingdeeVoucherRuleResponse.DimensionSpec("BANK_ACCOUNT", "ACCOUNT", null)))), List.of())));
        when(dimensionService.slotOf("BANK_ACCOUNT")).thenReturn("FF100002");

        BankAccount account = account("CMB");
        account.setKingdeeAccountNumber("11050160520009100036");
        KingdeeVoucherEntryDraft draft = firstDebit("服务费", "某供应商", account);

        assertEquals("11050160520009100036", draft.extraDimensions().get(0).value(),
                "银行账号维度走账户级映射（V41），不查值映射表");
        assertEquals("FF100002", draft.extraDimensions().get(0).slot());
        assertTrue(draft.extraDimensions().get(0).injectable());
    }

    @Test
    void unlinkedAccountGivesActionableNoteForBankDimension() {
        when(ruleService.listRules(true)).thenReturn(List.of(rule(List.of(
                new KingdeeVoucherRuleResponse.LineTemplate("6602.11", "管理费用", null, null, null,
                        "FULL", List.of(
                        new KingdeeVoucherRuleResponse.DimensionSpec("BANK_ACCOUNT", "ACCOUNT", null)))), List.of())));
        when(dimensionService.slotOf("BANK_ACCOUNT")).thenReturn("FF100002");

        BankAccount account = account("CMB"); // 未设 kingdeeAccountNumber
        KingdeeVoucherEntryDraft draft = firstDebit("服务费", "某供应商", account);

        assertNull(draft.extraDimensions().get(0).value());
        assertTrue(draft.extraDimensions().get(0).note().contains("银行账户"), draft.extraDimensions().get(0).note());
    }

    // ---------------- 单维度：编码优先、名称兜底（零回归） ----------------

    @Test
    void singleSupplierDimensionPrefersMappedCode() {
        when(ruleService.listRules(true)).thenReturn(List.of(rule(List.of(
                new KingdeeVoucherRuleResponse.LineTemplate("2202.01", "应付账款", "SUPPLIER", null, null, "FULL")),
                List.of())));
        when(dimensionService.resolveValue(eq("SUPPLIER"), anyString(), any())).thenReturn("VEN00777");

        assertEquals("VEN00777", firstDebit("服务费", "某某供应商").dimensionValue());
    }

    @Test
    void singleSupplierDimensionFallsBackToNameWhenUnmapped() {
        when(ruleService.listRules(true)).thenReturn(List.of(rule(List.of(
                new KingdeeVoucherRuleResponse.LineTemplate("2202.01", "应付账款", "SUPPLIER", null, null, "FULL")),
                List.of())));
        when(dimensionService.resolveValue(eq("SUPPLIER"), anyString(), any())).thenReturn(null);

        assertEquals("某某供应商", firstDebit("服务费", "某某供应商").dimensionValue(),
                "映射未配置时退回名称——既有 22 条规则此前即按名称推送，保持零回归");
    }

    // ---------------- 图虫系主体解析 ----------------

    @Test
    void tuchongCompanyResolvesToOrg410() {
        KingdeeOrgResolver resolver = new KingdeeOrgResolver();
        assertEquals("410", resolver.resolveOrgCode("上海图虫网络科技有限公司"));
        assertEquals("411", resolver.resolveOrgCode("上海映脉文化传播有限公司"));
        assertEquals("421", resolver.resolveOrgCode("浙江北分科技有限公司"), "「浙江北分」必须先于「浙江」匹配");
        assertEquals("420", resolver.resolveOrgCode("浙江某某科技有限公司"));
        assertEquals("710", resolver.resolveOrgCode("北京雪云锐创科技有限公司长沙分公司"),
                "分公司关键词仍须先于主公司");
    }

    @Test
    void tuchongRuleScopedTo410MatchesTuchongStatement() {
        when(ruleService.listRules(true)).thenReturn(List.of(rule410(List.of(
                new KingdeeVoucherRuleResponse.LineTemplate("6602.11", "管理费用", null, null, null, "FULL")),
                List.of())));

        KingdeeVoucherRulePreview preview = service.preview(
                statement("服务费", "某某供应商"),
                account("CMB"),
                company("上海图虫网络科技有限公司"));

        assertEquals(KingdeeVoucherMatchingService.ST_AUTO_FILL, preview.status(),
                "scope=410 的规则应命中图虫主体");
    }

    @Test
    void orgScopedRuleSkipsOtherCompany() {
        when(ruleService.listRules(true)).thenReturn(List.of(rule410(List.of(
                new KingdeeVoucherRuleResponse.LineTemplate("6602.11", "管理费用", null, null, null, "FULL")),
                List.of())));

        KingdeeVoucherRulePreview preview = service.preview(
                statement("服务费", "某某供应商"),
                account("CMB"),
                company("北京雪云锐创科技有限公司"));

        assertEquals(KingdeeVoucherMatchingService.ST_UNMATCHED, preview.status(),
                "主体不匹配（400 ≠ 410）时不得误用图虫规则");
    }

    // ---------------- fixtures ----------------

    private KingdeeVoucherEntryDraft firstDebit(String summary, String counterparty) {
        return firstDebit(summary, counterparty, account("CMB"));
    }

    private KingdeeVoucherEntryDraft firstDebit(String summary, String counterparty, BankAccount account) {
        KingdeeVoucherRulePreview preview = service.preview(statement(summary, counterparty), account,
                company("上海图虫网络科技有限公司"));
        assertNotNull(preview.candidates());
        assertTrue(!preview.candidates().isEmpty(), "预览应命中规则：" + preview.reason());
        return preview.candidates().get(0).debitLines().get(0);
    }

    private static KingdeeVoucherRuleResponse rule(List<KingdeeVoucherRuleResponse.LineTemplate> debits,
                                                   List<KingdeeVoucherRuleResponse.LineTemplate> credits) {
        return rule("ALL", debits, credits);
    }

    private static KingdeeVoucherRuleResponse rule410(List<KingdeeVoucherRuleResponse.LineTemplate> debits,
                                                      List<KingdeeVoucherRuleResponse.LineTemplate> credits) {
        return rule("410", debits, credits);
    }

    private static KingdeeVoucherRuleResponse rule(String scopeOrg,
                                                   List<KingdeeVoucherRuleResponse.LineTemplate> debits,
                                                   List<KingdeeVoucherRuleResponse.LineTemplate> credits) {
        return new KingdeeVoucherRuleResponse(1L, 99, "测试业务", "费用类", 10,
                List.of(scopeOrg), List.of(), "EXPENSE", null, null,
                new KingdeeVoucherRuleResponse.Match("ALL", List.of(
                        new KingdeeVoucherRuleResponse.Condition("SUMMARY", "CONTAINS", List.of("服务费")))),
                debits, credits, null, true, "单测夹具", null, null);
    }

    private static StatementRecord statement(String summary, String counterparty) {
        StatementRecord statement = new StatementRecord();
        statement.setId(1L);
        statement.setStatementNo("KVR-TEST-1");
        statement.setDirection("EXPENSE");
        statement.setAmount(new BigDecimal("100.00"));
        statement.setSummary(summary);
        statement.setCounterpartyName(counterparty);
        statement.setTransactionTime(LocalDateTime.parse("2026-09-21T10:00:00"));
        return statement;
    }

    private static BankAccount account(String bankCode) {
        BankAccount account = new BankAccount();
        account.setId(1L);
        account.setBankCode(bankCode);
        account.setAccountNumber("6222000011112222");
        return account;
    }

    private static Company company(String name) {
        Company company = new Company();
        company.setId(1L);
        company.setName(name);
        return company;
    }
}
