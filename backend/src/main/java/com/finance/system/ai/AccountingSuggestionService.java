package com.finance.system.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.ai.dto.AiAccountingSuggestionResponse;
import com.finance.system.ai.dto.VoucherEntry;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.common.tenant.CompanyScopeService;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.Company;
import com.finance.system.domain.entity.StatementRecord;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.CompanyMapper;
import com.finance.system.domain.mapper.StatementRecordMapper;
import com.finance.system.rbac.RbacService;
import com.finance.system.statement.voucherrule.KingdeeVoucherMatchingService;
import com.finance.system.statement.voucherrule.dto.KingdeeVoucherEntryDraft;
import com.finance.system.statement.voucherrule.dto.KingdeeVoucherRulePreview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    /** 系统默认提示词（W9 起可被 ai_prompt_override 覆盖，见 AiPromptCatalog）。 */
    public static final String SYSTEM_PROMPT = """
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
            "rationale":"判断依据（60字内）",
            "entries":[
              {"summary":"该分录摘要（与 suggestedSummary 一致或更具体）",
               "subjectCode":"该科目在你的建议中的编码，不确定就给空字符串",
               "subjectName":"科目名称，如 银行存款/应收账款/主营业务收入/管理费用-办公费",
               "direction":"DEBIT 或 CREDIT（借方/贷方）",
               "amount":金额数字,
               "confidence":该行科目判断的置信度0到1}]}
            entries 分录规则：收入（银行收款）→ 借：银行存款（DEBIT），贷：业务科目（CREDIT）；
            支出（银行付款）→ 借：业务科目（DEBIT），贷：银行存款（CREDIT）。银行存款行科目
            通常就是“银行存款”，置信度给 1.0；业务科目行给出你的判断与置信度。各分录 amount
            之和必须借贷相等（都等于流水金额）；一般两行，确需拆分时才多行且必须借贷平衡。
            如果用户消息里出现了「本笔流水命中的企业入账规则」，说明服务端规则引擎已经确定了借贷科目：
            你必须原样采用规则给出的科目名与方向（R{businessType} 与分录不得改写，金额等于流水金额），
            只负责补全 businessCategory / suggestedSummary / counterpartyType / settlementMethod /
            riskNotes，并在 rationale 开头写「命中规则 R{规则号}」；没有规则命中时按上面的财务常识独立判断。""";

    private final AiGatewayService gatewayService;
    private final AiPromptService promptService;
    private final StatementRecordMapper statementMapper;
    private final CompanyScopeService companyScope;
    private final ObjectMapper objectMapper;
    /** W10（WP-5）：规则引擎 —— 命中规则注入提示词，实现「规则优先、AI 兜底」。 */
    private final KingdeeVoucherMatchingService matchingService;
    private final BankAccountMapper bankAccountMapper;
    private final CompanyMapper companyMapper;
    /**
     * 跨公司数据权限（{@code bankdata:cross-company:view}）判定来源。
     *
     * <p>FIX-008（2026-09-21）：本类 {@link #requireInCompanyScope} 原先硬校验「流水公司 == 操作人公司」，
     * 与制证链路（{@code BankDataAccountingService}）的跨公司口径冲突。实测：账户 9（上海图虫，
     * companyId=2）的流水在 admin（companyId=1）下取 AI 建议必然 404 → 降级成模糊的
     * 「AI 建议不可用」；同公司的账户 7 全部成功。修复后与 {@code refreshAiSuggestion} 的
     * W3 口径（2026-09-18）完全一致。</p>
     */
    private final RbacService rbacService;

    /** 注入提示词的命中规则上限（token 预算控制）。 */
    private static final int MAX_HIT_RULES = 3;

    public AccountingSuggestionService(AiGatewayService gatewayService, AiPromptService promptService,
                                       StatementRecordMapper statementMapper,
                                       CompanyScopeService companyScope, ObjectMapper objectMapper,
                                       KingdeeVoucherMatchingService matchingService,
                                       BankAccountMapper bankAccountMapper,
                                       CompanyMapper companyMapper,
                                       RbacService rbacService) {
        this.gatewayService = gatewayService;
        this.promptService = promptService;
        this.statementMapper = statementMapper;
        this.companyScope = companyScope;
        this.objectMapper = objectMapper;
        this.matchingService = matchingService;
        this.bankAccountMapper = bankAccountMapper;
        this.companyMapper = companyMapper;
        this.rbacService = rbacService;
    }

    public AiAccountingSuggestionResponse suggest(Long statementId, Long userId) {
        AiEffectiveConfig config = gatewayService.auditedGuard(CAPABILITY, userId);
        StatementRecord statement = requireInCompanyScope(statementId, userId);
        // W10（WP-5）：先跑服务端规则引擎，把命中规则作为强约束注入提示词 —— 规则优先、AI 兜底。
        List<KingdeeVoucherRulePreview.Candidate> hits = hitRules(statement);
        // W9：系统提示词支持超管在页面覆盖（ai_prompt_override），无覆盖回落 SYSTEM_PROMPT。
        // W10：max_tokens 512 → 2048。原 512 会让「含 entries 分录 + rationale/riskNotes 的中文 JSON」
        // 被截断（线上 call-logs 实测 completionTokens 多次正好卡 512、responseSummary 为空），
        // 截断的半截 JSON 解析失败 → 业务层降级为「AI 建议不可用」，是用户反馈 AI 不可用的真因。
        LlmChatRequest request = new LlmChatRequest(CAPABILITY, promptService.resolve(CAPABILITY),
                buildUserPrompt(statement, hits), 0.2, 2048);
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
                result.durationMillis(),
                parseEntries(json, statement),
                hits.stream()
                        .map(hit -> new AiAccountingSuggestionResponse.HitRule(
                                hit.ruleNo(), hit.businessType(), hit.category()))
                        .toList());
    }

    /**
     * 服务端规则引擎预检（WP-5）：返回命中的入账规则（最多 {@link #MAX_HIT_RULES} 条，按规则优先级）。
     *
     * <p>规则引擎异常一律降级为空命中 —— 规则配置问题不应让整条 AI 制证链路失败，
     * 此时退化为纯 AI 建议（与 WP-5 之前的行为一致）。</p>
     */
    private List<KingdeeVoucherRulePreview.Candidate> hitRules(StatementRecord statement) {
        try {
            BankAccount account = statement.getBankAccountId() == null
                    ? null : bankAccountMapper.selectById(statement.getBankAccountId());
            Company company = statement.getCompanyId() == null
                    ? null : companyMapper.selectById(statement.getCompanyId());
            KingdeeVoucherRulePreview preview = matchingService.preview(statement, account, company);
            List<KingdeeVoucherRulePreview.Candidate> candidates = preview.candidates();
            if (candidates == null || candidates.isEmpty()) {
                return List.of();
            }
            return candidates.stream().limit(MAX_HIT_RULES).toList();
        } catch (Exception e) {
            log.warn("规则引擎预检失败，降级为纯 AI 建议：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 公司域校验（FIX-008：与制证/刷新 AI 建议口径对称）。
     *
     * <p>持有 {@code bankdata:cross-company:view} 的用户可对他司流水取 AI 建议——制证链路本就
     * 允许跨公司（有权限时），AI 建议若一刀切拒绝，有权限的用户在跨公司流水上会永远拿到
     * 「AI 建议不可用」（实测现象）。无权限用户对非本公司流水仍一律 404，不暴露存在性。</p>
     */
    private StatementRecord requireInCompanyScope(Long statementId, Long userId) {
        long companyId = companyScope.companyIdForUser(userId);
        boolean crossCompany = rbacService.permissionCodesForUser(userId)
                .contains("bankdata:cross-company:view");
        StatementRecord statement = statementMapper.selectById(statementId);
        if (statement == null || statement.getCompanyId() == null
                || (statement.getCompanyId() != companyId && !crossCompany)) {
            throw new BusinessException(404, "流水不存在或不在当前公司域内");
        }
        return statement;
    }

    /** 脱敏上下文：不含对手方账号、不含原始报文、不含公司主体；命中规则时附规则分录作为强约束。 */
    private String buildUserPrompt(StatementRecord s, List<KingdeeVoucherRulePreview.Candidate> hits) {
        StringBuilder prompt = new StringBuilder("流水信息：\n"
                + "- 方向：" + ("CREDIT".equalsIgnoreCase(s.getDirection()) ? "收入（借：银行存款）" : "支出")
                + "\n- 金额：" + s.getAmount() + " " + (s.getCurrency() == null ? "CNY" : s.getCurrency())
                + "\n- 交易时间：" + s.getTransactionTime()
                + "\n- 对手方名称：" + nullSafe(s.getCounterpartyName())
                + "\n- 摘要：" + nullSafe(s.getSummary()));
        if (hits != null && !hits.isEmpty()) {
            prompt.append("\n\n本笔流水命中的企业入账规则（服务端规则引擎判定，分录必须采用）：");
            for (KingdeeVoucherRulePreview.Candidate hit : hits) {
                prompt.append("\n- 规则 R").append(hit.ruleNo())
                        .append("（业务类型：").append(nullSafe(hit.businessType()))
                        .append("；类别：").append(nullSafe(hit.category()))
                        .append("；优先级 ").append(hit.priority()).append("）");
                appendRuleLines(prompt, "借", hit.debitLines());
                appendRuleLines(prompt, "贷", hit.creditLines());
            }
            prompt.append("\n请以上述规则的科目名与方向为准输出 entries，并在 rationale 开头注明命中规则号。");
        }
        prompt.append("\n请输出 JSON 建议。");
        return prompt.toString();
    }

    /** 规则分录行文本化（科目名 + 编码 + 金额口径），供提示词引用。 */
    private void appendRuleLines(StringBuilder prompt, String side,
                                 List<KingdeeVoucherEntryDraft> lines) {
        if (lines == null) return;
        for (KingdeeVoucherEntryDraft line : lines) {
            prompt.append("\n    ").append(side).append("：")
                    .append(nullSafe(line.accountName()))
                    .append(line.account() == null || line.account().isBlank() ? "" : "（" + line.account() + "）")
                    .append(line.manual() ? "【金额待人工确认】" : "");
        }
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

    /**
     * 分录解析（V33）：entries 数组合法（≥1 行、科目名/方向/金额齐全、方向枚举合法）→ 原样构建；
     * 缺失或非法 → 用单科目建议降级构造两行（银行存款行 + 业务科目行），<b>不因 entries 缺失而
     * 502</b>（兼容旧模型输出）。降级也构造不出业务科目行（suggestedSubject 空）时返回空列表。
     * 金额借贷是否平衡不做硬校验——AI 只建议，不平衡由凭证详情页标注并人工修正。
     */
    private List<VoucherEntry> parseEntries(JsonNode json, StatementRecord statement) {
        JsonNode nodes = json.path("entries");
        if (nodes.isArray() && !nodes.isEmpty()) {
            List<VoucherEntry> parsed = new ArrayList<>();
            boolean valid = true;
            for (JsonNode node : nodes) {
                String subjectName = text(node, "subjectName");
                String direction = text(node, "direction");
                BigDecimal amount = node.path("amount").isNumber() ? node.path("amount").decimalValue() : null;
                String normalized = direction == null ? "" : direction.trim().toUpperCase(Locale.ROOT);
                if (subjectName == null || amount == null || amount.signum() <= 0
                        || (!VoucherEntry.DIRECTION_DEBIT.equals(normalized)
                            && !VoucherEntry.DIRECTION_CREDIT.equals(normalized))) {
                    valid = false;
                    break;
                }
                parsed.add(new VoucherEntry(
                        text(node, "summary"),
                        text(node, "subjectCode"),
                        subjectName.trim(),
                        normalized,
                        amount,
                        node.path("confidence").isNumber() ? node.path("confidence").asDouble() : null));
            }
            if (valid) {
                return parsed;
            }
            log.info("AI 建议 entries 非法，降级为单科目两行分录（statementId={}）", statement.getId());
        }
        return fallbackEntries(json, statement);
    }

    /** 旧输出降级：单科目建议 → 两行分录（收入=借银行存款/贷业务科目；支出反之）。 */
    private List<VoucherEntry> fallbackEntries(JsonNode json, StatementRecord statement) {
        String subject = text(json, "suggestedSubject");
        if (subject == null) {
            return List.of();
        }
        BigDecimal amount = statement.getAmount() == null ? BigDecimal.ZERO : statement.getAmount();
        String summary = text(json, "suggestedSummary");
        Double confidence = json.path("confidence").isNumber() ? json.path("confidence").asDouble() : null;
        boolean income = "CREDIT".equalsIgnoreCase(statement.getDirection());
        VoucherEntry bankLine = new VoucherEntry(summary, null, "银行存款",
                income ? VoucherEntry.DIRECTION_DEBIT : VoucherEntry.DIRECTION_CREDIT, amount, 1.0);
        VoucherEntry businessLine = new VoucherEntry(summary, null, subject,
                income ? VoucherEntry.DIRECTION_CREDIT : VoucherEntry.DIRECTION_DEBIT, amount, confidence);
        return income ? List.of(bankLine, businessLine) : List.of(businessLine, bankLine);
    }

    private static String text(JsonNode json, String field) {
        String value = json.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private static String nullSafe(String value) {
        return value == null ? "（无）" : value;
    }
}
