package com.finance.system.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.ai.dto.AiAccountingSuggestionResponse;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.common.tenant.CompanyScopeService;
import com.finance.system.domain.entity.StatementRecord;
import com.finance.system.domain.mapper.StatementRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * A1 智能入账建议（规划文档 2026-09-11 首发能力，衔接金蝶制证的「AI 兜底预填」）。
 *
 * <p>流程：守卫（ai:use + capability 开关 + 限频）→ 公司域内取流水 → 构建脱敏上下文
 * → LLM 往返（走 {@link AiGatewayService#auditedChat} 统一审计）→ 严格 JSON 解析。
 * 铁律：<b>AI 只建议、不执行</b>——不改流水任何状态字段，结论仍走人工复核与制证。</p>
 *
 * <p>数据出域口径（规划 D3 = 脱敏出域）：只送方向/金额/币种/时间/对手方名称/摘要，
 * 对手方账号（库里本就是脱敏值）不送、原始报文不送、公司主体名称不送。</p>
 */
@Service
public class AccountingSuggestionService {

    private static final Logger log = LoggerFactory.getLogger(AccountingSuggestionService.class);

    /** 能力名：AI 设置页能力开关与审计表中的 capability 标识。 */
    public static final String CAPABILITY = "accounting-suggestion";

    private static final String SYSTEM_PROMPT = """
            你是中国小企业的资深财务会计，负责银行流水的人工复核辅助。你会收到一条银行流水
            （方向、金额、币种、交易时间、对手方名称、摘要）。请基于财务常识推断这笔交易最可能的
            业务性质，并给出金蝶入账的预填建议。你必须只输出一个 JSON 对象，不要输出任何其它
            文字、解释或 markdown 代码块。JSON 字段固定为：
            {"businessCategory":"业务类别，如 货款收入/差旅费/办公用品/工资/手续费/往来款 等",
            "suggestedSummary":"建议的入账摘要（20字内，中文）",
            "counterpartyType":"CUSTOMER|SUPPLIER|EMPLOYEE|OTHER 之一",
            "settlementMethod":"建议结算方式，如 银行转账/现金/承兑汇票 等",
            "suggestedSubject":"建议会计科目（如 应收账款/管理费用-办公费/主营业务收入 等）",
            "riskNotes":"该笔交易需要注意的风险点（无则写“无”）",
            "confidence":0到1的小数,
            "rationale":"判断依据（60字内）"}""";

    private final AiGatewayService gatewayService;
    private final StatementRecordMapper statementMapper;
    private final CompanyScopeService companyScope;
    private final ObjectMapper objectMapper;

    public AccountingSuggestionService(AiGatewayService gatewayService, StatementRecordMapper statementMapper,
                                       CompanyScopeService companyScope, ObjectMapper objectMapper) {
        this.gatewayService = gatewayService;
        this.statementMapper = statementMapper;
        this.companyScope = companyScope;
        this.objectMapper = objectMapper;
    }

    public AiAccountingSuggestionResponse suggest(Long statementId, Long userId) {
        AiEffectiveConfig config = gatewayService.auditedGuard(CAPABILITY, userId);
        StatementRecord statement = requireInCompanyScope(statementId, userId);
        LlmChatRequest request = new LlmChatRequest(CAPABILITY, SYSTEM_PROMPT,
                buildUserPrompt(statement), 0.2, 512);
        LlmChatResult result = gatewayService.auditedChat(CAPABILITY, userId, config, request);
        JsonNode json = parseSuggestionJson(result.content());
        return new AiAccountingSuggestionResponse(
                statementId,
                text(json, "businessCategory"),
                text(json, "suggestedSummary"),
                text(json, "counterpartyType"),
                text(json, "settlementMethod"),
                text(json, "suggestedSubject"),
                text(json, "riskNotes"),
                json.path("confidence").isNumber() ? json.path("confidence").asDouble() : null,
                text(json, "rationale"),
                result.model(),
                result.durationMillis());
    }

    /** 公司域校验：跨公司流水一律 404（不暴露存在性）。 */
    private StatementRecord requireInCompanyScope(Long statementId, Long userId) {
        long companyId = companyScope.companyIdForUser(userId);
        StatementRecord statement = statementMapper.selectById(statementId);
        if (statement == null || statement.getCompanyId() == null
                || statement.getCompanyId() != companyId) {
            throw new BusinessException(404, "流水不存在或不在当前公司域内");
        }
        return statement;
    }

    /** 脱敏上下文：不含对手方账号、不含原始报文、不含公司主体。 */
    private String buildUserPrompt(StatementRecord s) {
        return "流水信息：\n"
                + "- 方向：" + ("CREDIT".equalsIgnoreCase(s.getDirection()) ? "收入（借：银行存款）" : "支出")
                + "\n- 金额：" + s.getAmount() + " " + (s.getCurrency() == null ? "CNY" : s.getCurrency())
                + "\n- 交易时间：" + s.getTransactionTime()
                + "\n- 对手方名称：" + nullSafe(s.getCounterpartyName())
                + "\n- 摘要：" + nullSafe(s.getSummary())
                + "\n请输出 JSON 建议。";
    }

    /** 严格解析：剥掉可能的 markdown 围栏后必须命中全部固定字段，否则 502。 */
    private JsonNode parseSuggestionJson(String content) {
        String cleaned = content == null ? "" : content.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("```\\s*$", "").trim();
        }
        int braceStart = cleaned.indexOf('{');
        int braceEnd = cleaned.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            cleaned = cleaned.substring(braceStart, braceEnd + 1);
        }
        try {
            JsonNode json = objectMapper.readTree(cleaned);
            for (String field : new String[]{"businessCategory", "suggestedSummary", "counterpartyType"}) {
                if (json.path(field).asText("").isBlank()) {
                    throw new BusinessException(502, "AI 建议缺少必要字段 " + field + "（模型未按约定 JSON 返回）");
                }
            }
            return json;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("AI 入账建议解析失败：{}", e.getMessage());
            throw new BusinessException(502, "AI 建议解析失败（响应非约定 JSON），可重试一次或人工判断");
        }
    }

    private static String text(JsonNode json, String field) {
        String value = json.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private static String nullSafe(String value) {
        return value == null ? "（无）" : value;
    }
}
