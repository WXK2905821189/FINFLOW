package com.finance.system.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.ai.dto.AiAccountingSuggestionResponse;
import com.finance.system.common.tenant.CompanyScopeService;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.Company;
import com.finance.system.domain.entity.StatementRecord;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.CompanyMapper;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.mapper.StatementRecordMapper;
import com.finance.system.rbac.RbacService;
import com.finance.system.statement.voucherrule.KingdeeVoucherMatchingService;
import com.finance.system.statement.voucherrule.dto.KingdeeVoucherEntryDraft;
import com.finance.system.statement.voucherrule.dto.KingdeeVoucherRulePreview;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W10（WP-5）：规则注入 AI 制证。
 *
 * <p>契约：①命中规则时，规则的分录行必须进入提示词（规则优先于 AI 自由判断）；
 * ②规则引擎自身异常时降级为纯 AI 建议（不因规则配置问题阻断制证链路）。</p>
 */
@ExtendWith(MockitoExtension.class)
class AccountingSuggestionRuleInjectionTest {

    private static final String CAPABILITY = AccountingSuggestionService.CAPABILITY;
    private static final long USER_ID = 1L;
    private static final long STATEMENT_ID = 100L;

    @Mock private AiGatewayService gatewayService;
    @Mock private AiPromptService promptService;
    @Mock private StatementRecordMapper statementMapper;
    @Mock private CompanyScopeService companyScope;
    @Mock private KingdeeVoucherMatchingService matchingService;
    @Mock private BankAccountMapper bankAccountMapper;
    @Mock private CompanyMapper companyMapper;
    @Mock private RbacService rbacService;
    @Mock private AiEffectiveConfig config;

    private AccountingSuggestionService service;

    @BeforeEach
    void setUp() {
        service = new AccountingSuggestionService(gatewayService, promptService, statementMapper,
                companyScope, new ObjectMapper(), matchingService, bankAccountMapper, companyMapper,
                rbacService);
    }

    @Test
    void hitRuleLinesAreInjectedIntoPrompt() {
        stubHappyPath();
        when(bankAccountMapper.selectById(7L)).thenReturn(new BankAccount());
        when(companyMapper.selectById(1L)).thenReturn(new Company());
        when(matchingService.preview(any(), any(), any())).thenReturn(previewWithCandidate());

        AiAccountingSuggestionResponse response = service.suggest(STATEMENT_ID, USER_ID);

        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(gatewayService).auditedChat(eq(CAPABILITY), eq(USER_ID), eq(config), captor.capture());
        String prompt = captor.getValue().userPrompt();
        assertTrue(prompt.contains("本笔流水命中的企业入账规则"), "提示词须标明命中规则区块：" + prompt);
        assertTrue(prompt.contains("应收账款-罗德岛"), "规则借方科目须进入提示词：" + prompt);
        assertTrue(prompt.contains("银行存款"), "规则贷方科目须进入提示词：" + prompt);
        assertTrue(prompt.contains("R2"), "提示词须带规则号");

        assertEquals(1, response.hitRules().size(), "响应须回传命中规则供前端提示");
        assertEquals(2, response.hitRules().get(0).ruleNo());
        assertEquals("集团主体往来", response.hitRules().get(0).businessType());
    }

    @Test
    void noHitRuleKeepsPromptClean() {
        stubHappyPath();
        when(bankAccountMapper.selectById(7L)).thenReturn(new BankAccount());
        when(companyMapper.selectById(1L)).thenReturn(new Company());
        when(matchingService.preview(any(), any(), any()))
                .thenReturn(new KingdeeVoucherRulePreview(STATEMENT_ID, "ST-100", "CREDIT",
                        new BigDecimal("12800.00"), "UNMATCHED", "无命中规则", List.of()));

        AiAccountingSuggestionResponse response = service.suggest(STATEMENT_ID, USER_ID);

        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(gatewayService).auditedChat(eq(CAPABILITY), eq(USER_ID), eq(config), captor.capture());
        assertFalse(captor.getValue().userPrompt().contains("命中的企业入账规则"), "未命中时不得注入规则区块");
        assertTrue(response.hitRules().isEmpty());
    }

    /** 规则引擎异常（如规则 JSON 损坏）必须降级为纯 AI，不能让整条制证链路失败。 */
    @Test
    void ruleEngineFailureFallsBackToPureAi() {
        stubHappyPath();
        when(bankAccountMapper.selectById(7L)).thenReturn(new BankAccount());
        when(companyMapper.selectById(1L)).thenReturn(new Company());
        when(matchingService.preview(any(), any(), any()))
                .thenThrow(new IllegalStateException("rule json corrupt"));

        AiAccountingSuggestionResponse response = service.suggest(STATEMENT_ID, USER_ID);

        assertEquals("货款收入", response.businessCategory(), "降级后仍应返回 AI 建议");
        assertTrue(response.hitRules().isEmpty());
        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(gatewayService).auditedChat(eq(CAPABILITY), eq(USER_ID), eq(config), captor.capture());
        assertFalse(captor.getValue().userPrompt().contains("命中的企业入账规则"));
    }

    /**
     * FIX-008（2026-09-21）：持 {@code bankdata:cross-company:view} 的用户可对他司流水取 AI 建议。
     *
     * <p>线上实测：账户 9（上海图虫，companyId=2）的流水在 admin（companyId=1）下取建议被 404
     * 拒绝 → 降级成模糊的「AI 建议不可用」，而同公司的账户 7 全部成功。修复后与
     * {@code BankDataAccountingService#refreshAiSuggestion} 的 W3 口径一致。</p>
     */
    @Test
    void crossCompanyPermissionAllowsSuggestionOnOtherCompanyStatement() {
        stubHappyPath();
        when(rbacService.permissionCodesForUser(anyLong()))
                .thenReturn(List.of("bankdata:cross-company:view"));
        StatementRecord other = statement();
        other.setCompanyId(2L);
        when(statementMapper.selectById(STATEMENT_ID)).thenReturn(other);
        when(bankAccountMapper.selectById(7L)).thenReturn(new BankAccount());
        when(companyMapper.selectById(1L)).thenReturn(new Company());
        when(matchingService.preview(any(), any(), any()))
                .thenReturn(new KingdeeVoucherRulePreview(STATEMENT_ID, "ST-100", "CREDIT",
                        new BigDecimal("12800.00"), "UNMATCHED", "无命中规则", List.of()));

        AiAccountingSuggestionResponse response = service.suggest(STATEMENT_ID, USER_ID);

        assertEquals("货款收入", response.businessCategory(), "有跨公司权限时必须取到 AI 建议");
    }

    /** FIX-008：无跨公司权限时，他司流水仍 404（不暴露存在性）。 */
    @Test
    void withoutCrossCompanyPermissionOtherCompanyStatementIsRejected() {
        when(companyScope.companyIdForUser(anyLong())).thenReturn(1L);
        when(gatewayService.auditedGuard(CAPABILITY, USER_ID)).thenReturn(config);
        StatementRecord other = statement();
        other.setCompanyId(2L);
        when(statementMapper.selectById(STATEMENT_ID)).thenReturn(other);
        // rbacService 未 stub → Mockito 对 List 返回空集合 ⇒ 无跨公司权限

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.suggest(STATEMENT_ID, USER_ID));

        assertEquals(404, ex.getCode());
    }

    // ---- helpers ----

    private void stubHappyPath() {
        when(companyScope.companyIdForUser(anyLong())).thenReturn(1L);
        when(statementMapper.selectById(STATEMENT_ID)).thenReturn(statement());
        when(promptService.resolve(CAPABILITY)).thenReturn("SYS");
        when(gatewayService.auditedGuard(CAPABILITY, USER_ID)).thenReturn(config);
        when(gatewayService.auditedChat(eq(CAPABILITY), eq(USER_ID), eq(config), any()))
                .thenReturn(new LlmChatResult(suggestionJson(), "mock-model", 100, 200, 12L));
    }

    private static StatementRecord statement() {
        StatementRecord record = new StatementRecord();
        record.setId(STATEMENT_ID);
        record.setCompanyId(1L);
        record.setBankAccountId(7L);
        record.setStatementNo("ST-100");
        record.setDirection("CREDIT");
        record.setAmount(new BigDecimal("12800.00"));
        record.setCurrency("CNY");
        record.setTransactionTime(LocalDateTime.now().minusHours(3));
        record.setCounterpartyName("罗德岛贸易");
        record.setSummary("收罗德岛货款");
        return record;
    }

    private static KingdeeVoucherRulePreview previewWithCandidate() {
        List<KingdeeVoucherEntryDraft> debits = List.of(new KingdeeVoucherEntryDraft(
                "DEBIT", "1122", "应收账款-罗德岛", null, null, new BigDecimal("12800.00"), null, false));
        List<KingdeeVoucherEntryDraft> credits = List.of(new KingdeeVoucherEntryDraft(
                "CREDIT", "1002", "银行存款", null, null, new BigDecimal("12800.00"), null, false));
        KingdeeVoucherRulePreview.Candidate candidate = new KingdeeVoucherRulePreview.Candidate(
                2, "集团主体往来", "往来类", 30, false, debits, credits, null);
        return new KingdeeVoucherRulePreview(STATEMENT_ID, "ST-100", "CREDIT",
                new BigDecimal("12800.00"), "AUTO_FILL", null, List.of(candidate));
    }

    private static String suggestionJson() {
        return """
                {"businessCategory":"货款收入","suggestedSummary":"收罗德岛货款","counterpartyType":"CUSTOMER",
                 "settlementMethod":"银行转账","suggestedSubject":"应收账款-罗德岛","riskNotes":"无",
                 "confidence":0.9,"rationale":"命中规则 R2",
                 "entries":[{"summary":"收罗德岛货款","subjectCode":"1122","subjectName":"应收账款-罗德岛",
                 "direction":"DEBIT","amount":12800.00,"confidence":0.95},
                 {"summary":"收罗德岛货款","subjectCode":"1002","subjectName":"银行存款",
                 "direction":"CREDIT","amount":12800.00,"confidence":1.0}]}""";
    }
}
