package com.finance.system.bank;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.ai.AiEffectiveConfig;
import com.finance.system.ai.AiGatewayService;
import com.finance.system.ai.LlmChatRequest;
import com.finance.system.ai.LlmChatResult;
import com.finance.system.bank.dto.AiCompanySuggestionResponse;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.Company;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.CompanyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 公司主体归类建议单测：能力守卫接入、脱敏出域（账号只送后4位）、
 * 严格 JSON 解析（围栏剥离/幻觉 accountId 过滤/空建议 502）。
 */
class AiCompanyClassifierServiceTest {

    private static final Long USER_ID = 7L;

    private AiGatewayService gatewayService;
    private BankAccountMapper bankAccountMapper;
    private CompanyMapper companyMapper;
    private AiCompanyClassifierService service;
    private AiEffectiveConfig config;

    private static BankAccount account(long id, String name, String number) {
        BankAccount account = new BankAccount();
        account.setId(id);
        account.setAccountName(name);
        account.setAccountNumber(number);
        account.setBankCode("CITIC");
        account.setCurrency("CNY");
        account.setStatus("ACTIVE");
        return account;
    }

    @BeforeEach
    void setUp() {
        gatewayService = mock(AiGatewayService.class);
        bankAccountMapper = mock(BankAccountMapper.class);
        companyMapper = mock(CompanyMapper.class);
        service = new AiCompanyClassifierService(gatewayService, bankAccountMapper, companyMapper,
                new ObjectMapper());
        config = new AiEffectiveConfig(true, "https://llm.example", "k-secret", "test-model",
                30_000, 1, Map.of("company-classification", true), "openai-compatible", "在线配置");
    }

    private void stubUnfiled(BankAccount... accounts) {
        when(bankAccountMapper.selectList(any())).thenReturn(List.of(accounts));
        when(companyMapper.selectList(any())).thenReturn(List.of());
        when(gatewayService.auditedGuard(AiCompanyClassifierService.CAPABILITY, USER_ID)).thenReturn(config);
    }

    private void stubChat(String content) {
        when(gatewayService.auditedChat(eq(AiCompanyClassifierService.CAPABILITY), eq(USER_ID),
                eq(config), any(LlmChatRequest.class))).thenReturn(
                new LlmChatResult(content, "test-model", 10, 20, 50L));
    }

    @Test
    void noUnfiledAccountsSkipsLlm() {
        when(bankAccountMapper.selectList(any())).thenReturn(List.of());
        AiCompanySuggestionResponse response = service.suggest(USER_ID);
        assertTrue(response.suggestions().isEmpty());
        verify(gatewayService, never()).auditedChat(any(), any(), any(), any());
    }

    @Test
    void parsesPlainJsonAndMasksAccountNumber() {
        BankAccount a1 = account(1L, "北京雪云锐创科技有限公司-中信基本户", "6229000011111234");
        stubUnfiled(a1);
        stubChat("{\"suggestions\":[{\"accountId\":1,\"companyName\":\"北京雪云锐创科技有限公司\","
                + "\"confidence\":0.92,\"reason\":\"户名前缀完全匹配\"}]}");
        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        AiCompanySuggestionResponse response = service.suggest(USER_ID);
        verify(gatewayService).auditedChat(eq(AiCompanyClassifierService.CAPABILITY), eq(USER_ID),
                eq(config), captor.capture());
        // 脱敏铁律：完整账号不出域
        assertTrue(!captor.getValue().userPrompt().contains("6229000011111234"));
        assertTrue(captor.getValue().userPrompt().contains("1234"));
        assertEquals(1, response.suggestions().size());
        assertEquals("北京雪云锐创科技有限公司", response.suggestions().get(0).suggestedCompanyName());
        assertEquals(0.92, response.suggestions().get(0).confidence());
        assertEquals("test-model", response.model());
    }

    @Test
    void stripsMarkdownFence() {
        stubUnfiled(account(2L, "上海测试贸易有限公司-工行一般户", "9888000000556677"));
        stubChat("```json\n{\"suggestions\":[{\"accountId\":2,\"companyName\":\"上海测试贸易有限公司\","
                + "\"confidence\":0.8,\"reason\":\"户名匹配\"}]}\n```");
        assertEquals(1, service.suggest(USER_ID).suggestions().size());
    }

    @Test
    void hallucinatedAccountIdsAreDropped() {
        stubUnfiled(account(3L, "真实存在的账户", "1111222233334444"));
        stubChat("{\"suggestions\":[{\"accountId\":99,\"companyName\":\"幻觉公司\",\"confidence\":0.9,\"reason\":\"x\"},"
                + "{\"accountId\":3,\"companyName\":\"真实公司\",\"confidence\":0.7,\"reason\":\"ok\"}]}");
        List<AiCompanySuggestionResponse.Suggestion> suggestions = service.suggest(USER_ID).suggestions();
        assertEquals(1, suggestions.size());
        assertEquals(3L, suggestions.get(0).accountId());
        assertEquals("真实公司", suggestions.get(0).suggestedCompanyName());
    }

    @Test
    void blankSuggestionsFails502AfterRetry() {
        stubUnfiled(account(4L, "某账户", "1234"));
        stubChat("{\"suggestions\":[]}");
        BusinessException thrown = assertThrows(BusinessException.class, () -> service.suggest(USER_ID));
        assertTrue(thrown.getMessage().contains("suggestions"));
        assertTrue(thrown.getMessage().contains("已自动重试 1 次"));
        // 首次 + 自动重试 = 两次 LLM 往返（审计各记一条）
        verify(gatewayService, org.mockito.Mockito.times(2)).auditedChat(eq(AiCompanyClassifierService.CAPABILITY),
                eq(USER_ID), eq(config), any(LlmChatRequest.class));
    }

    @Test
    void nonJsonResponseFails502() {
        stubUnfiled(account(5L, "某账户", "5678"));
        stubChat("抱歉，我无法完成该任务。");
        BusinessException thrown = assertThrows(BusinessException.class, () -> service.suggest(USER_ID));
        assertTrue(thrown.getMessage().contains("解析失败"));
        // 最终失败的消息携带模型响应片段，UI 即时可见根因
        assertTrue(thrown.getMessage().contains("抱歉"));
    }

    @Test
    void retryRecoversFromFormatDrift() {
        stubUnfiled(account(8L, "格式漂移恢复账户", "7777"));
        // 第一次输出混入说明文字（偶发格式漂移），自动重试返回约定 JSON → 成功，用户无需手点
        when(gatewayService.auditedChat(eq(AiCompanyClassifierService.CAPABILITY), eq(USER_ID),
                eq(config), any(LlmChatRequest.class))).thenReturn(
                new LlmChatResult("好的，以下是归类建议：{\"suggestions\":[]}", "test-model", 10, 20, 50L),
                new LlmChatResult("{\"suggestions\":[{\"accountId\":8,\"companyName\":\"恢复公司\",\"confidence\":0.9,\"reason\":\"r\"}]}",
                        "test-model", 10, 20, 50L));
        AiCompanySuggestionResponse response = service.suggest(USER_ID);
        assertEquals(1, response.suggestions().size());
        assertEquals("恢复公司", response.suggestions().get(0).suggestedCompanyName());
        verify(gatewayService, org.mockito.Mockito.times(2)).auditedChat(eq(AiCompanyClassifierService.CAPABILITY),
                eq(USER_ID), eq(config), any(LlmChatRequest.class));
    }

    @Test
    void singleObjectWithoutSuggestionsArrayIsTolerated() {
        stubUnfiled(account(9L, "单对象账户", "9998"));
        stubChat("{\"accountId\":9,\"companyName\":\"单对象公司\",\"confidence\":0.5,\"reason\":\"r\"}");
        assertEquals(1, service.suggest(USER_ID).suggestions().size());
    }

    @Test
    void missingFieldsTolerated() {
        stubUnfiled(account(6L, "某账户", "8888"));
        stubChat("{\"suggestions\":[{\"accountId\":6,\"companyName\":\"公司A\"}]}");
        AiCompanySuggestionResponse.Suggestion s = service.suggest(USER_ID).suggestions().get(0);
        assertNull(s.confidence());
        assertNull(s.reason());
    }

    @Test
    void activeCompaniesListedInPrompt() {
        BankAccount a1 = account(7L, "某账户", "9999");
        stubUnfiled(a1);
        when(companyMapper.selectList(any())).thenReturn(List.of(company(11L, "已有公司甲"), company(12L, "已有公司乙")));
        stubChat("{\"suggestions\":[{\"accountId\":7,\"companyName\":\"已有公司甲\",\"confidence\":0.9,\"reason\":\"r\"}]}");
        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        service.suggest(USER_ID);
        verify(gatewayService).auditedChat(eq(AiCompanyClassifierService.CAPABILITY), eq(USER_ID),
                eq(config), captor.capture());
        assertTrue(captor.getValue().userPrompt().contains("已有公司甲"));
        assertTrue(captor.getValue().userPrompt().contains("已有公司乙"));
    }

    private static Company company(long id, String name) {
        Company company = new Company();
        company.setId(id);
        company.setName(name);
        company.setStatus("ACTIVE");
        return company;
    }
}
