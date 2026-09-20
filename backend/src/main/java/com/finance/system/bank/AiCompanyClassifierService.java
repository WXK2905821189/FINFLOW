package com.finance.system.bank;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 公司主体归类建议（V32，2026-09-17 需求：账户名称已可对应公司主体，一键自动建档）。
 *
 * <p>流程：守卫（ai:use + bank:manage 在 Controller 层；capability 开关在 guard）→
 * 取未归档账户与现有公司 → 构建脱敏上下文 → LLM 往返（{@link AiGatewayService#auditedChat}
 * 统一审计）→ 严格 JSON 解析。铁律与 A1 一致：<b>AI 只建议、不执行</b>——应用走
 * ai-apply 端点，且仅携带用户在预览中勾选的行。</p>
 *
 * <p>2026-09-17 增强（用户报障：偶发「缺少 suggestions 数组」要手点好几遍）：
 * <ul>
 *   <li>解析失败<b>服务端自动重试 1 次</b>（附纠正指令的强化提示词），两次都失败才抛 502；</li>
 *   <li>容错模型把单条建议直接输出为对象（无 suggestions 包装）的形态；</li>
 *   <li>最终失败的错误消息携带<b>模型响应片段</b>（≤200 字），UI 上即时可见根因；
 *       完整往返原文在 系统管理→AI状态页 调用审计（ai_call_log.response_summary，≤900 字）。</li>
 * </ul></p>
 *
 * <p>数据出域口径：只送账户名称、银行代码、账号后 4 位、币种与现有公司名列表；
 * 完整账号、余额、流水一律不出域。归档落点为 company 表（V30 口径：业务下拉以
 * 「账户与主体归档」为准，字典中心不参与）。</p>
 */
@Service
public class AiCompanyClassifierService {

    private static final Logger log = LoggerFactory.getLogger(AiCompanyClassifierService.class);

    /** 能力名：AI 设置页能力开关与审计表中的 capability 标识。 */
    public static final String CAPABILITY = "company-classification";

    /** 错误消息中携带的模型响应片段上限（UI 即时定位根因用，完整原文看调用审计）。 */
    private static final int SNIPPET_LIMIT = 200;

    /** 系统默认提示词（W9 起可被 ai_prompt_override 覆盖，见 AiPromptCatalog）。 */
    public static final String SYSTEM_PROMPT = """
            你是中国企业的财务数据治理助手，负责把银行账户归入正确的公司主体档案。你会收到：
            1) 待归档的银行账户列表（账户名称、银行、账号后4位、币种）；2) 系统中已有的公司主体名列表。
            银行账户名称通常包含公司主体线索（如「XX科技有限公司-招行基本户」应归入「XX科技有限公司」）。
            规则：每个账户给出一个最可能的公司主体名（可与已有公司名完全一致=归入已有档案；
            若都不匹配则给出建议新建的公司名，用规范全称，不要凭空编造不相关的公司）。
            你必须只输出一个 JSON 对象，不要输出任何其它文字或 markdown 代码块。JSON 格式固定为：
            {"suggestions":[{"accountId":账户ID数字,"companyName":"公司主体全称",
            "confidence":0到1的小数,"reason":"判断依据（40字内）"}]}
            suggestions 必须覆盖输入的每一个账户，accountId 原样使用输入值。""";

    private final AiGatewayService gatewayService;
    private final com.finance.system.ai.AiPromptService promptService;
    private final BankAccountMapper bankAccountMapper;
    private final CompanyMapper companyMapper;
    private final ObjectMapper objectMapper;

    public AiCompanyClassifierService(AiGatewayService gatewayService,
                                      com.finance.system.ai.AiPromptService promptService,
                                      BankAccountMapper bankAccountMapper,
                                      CompanyMapper companyMapper, ObjectMapper objectMapper) {
        this.gatewayService = gatewayService;
        this.promptService = promptService;
        this.bankAccountMapper = bankAccountMapper;
        this.companyMapper = companyMapper;
        this.objectMapper = objectMapper;
    }

    public AiCompanySuggestionResponse suggest(Long userId) {
        // @TableLogic（V32）自动过滤已删除账户；未归档 = 未挂任何公司档案的账户。
        List<BankAccount> unfiled = bankAccountMapper.selectList(new LambdaQueryWrapper<BankAccount>()
                .isNull(BankAccount::getCompanyId)
                .orderByAsc(BankAccount::getId));
        if (unfiled.isEmpty()) {
            return new AiCompanySuggestionResponse(List.of(), null, null);
        }
        List<Company> companies = companyMapper.selectList(new LambdaQueryWrapper<Company>()
                .eq(Company::getStatus, "ACTIVE")
                .orderByAsc(Company::getId));
        AiEffectiveConfig config = gatewayService.auditedGuard(CAPABILITY, userId);
        String userPrompt = buildUserPrompt(unfiled, companies);
        // W9：系统提示词支持超管在页面覆盖（ai_prompt_override），无覆盖回落 SYSTEM_PROMPT。
        String systemPrompt = promptService.resolve(CAPABILITY);
        // W10：max_tokens 2048 → 4096。输出量随「未归类账户数」线性增长，线上 call-logs 实测
        // 出现过 completionTokens 正好卡 2048（截顶）的记录，截断会使本次归类整体解析失败。
        LlmChatResult result = gatewayService.auditedChat(CAPABILITY, userId, config,
                new LlmChatRequest(CAPABILITY, systemPrompt, userPrompt, 0.1, 4096));
        List<AiCompanySuggestionResponse.Suggestion> suggestions;
        try {
            suggestions = parseSuggestions(result.content(), unfiled);
        } catch (BusinessException first) {
            // 自动重试一次：LLM 偶发格式漂移（输出说明文字/改用中文键/单对象），纠正指令后再试。
            log.warn("AI 归类建议首次解析失败，自动重试：{}", first.getMessage());
            LlmChatResult retry = gatewayService.auditedChat(CAPABILITY, userId, config,
                    new LlmChatRequest(CAPABILITY, systemPrompt,
                            userPrompt + RETRY_NUDGE.formatted(first.getMessage()), 0.1, 4096));
            try {
                suggestions = parseSuggestions(retry.content(), unfiled);
            } catch (BusinessException second) {
                throw new BusinessException(502, "AI 归类建议解析失败（已自动重试 1 次，模型仍未按约定 JSON 返回）。"
                        + "模型响应片段：" + snippet(retry.content()));
            }
        }
        return new AiCompanySuggestionResponse(suggestions, result.model(), result.durationMillis());
    }

    /** 重试提示词后缀：%s = 首次失败原因（不含用户数据）。 */
    private static final String RETRY_NUDGE = """

            注意：上一次输出不符合约定 JSON（%s）。请严格只输出一个 JSON 对象，\
            顶层键为 suggestions（对象数组），元素键为 accountId/companyName/confidence/reason，\
            不要输出任何其它文字、markdown 围栏或解释。""";

    /** 脱敏上下文：账户名/银行/账号后4位/币种 + 已有公司名；完整账号、余额不出域。 */
    private String buildUserPrompt(List<BankAccount> accounts, List<Company> companies) {
        StringBuilder sb = new StringBuilder("待归档账户：\n");
        for (BankAccount account : accounts) {
            sb.append("- accountId=").append(account.getId())
                    .append("，账户名称：").append(account.getAccountName())
                    .append("，银行：").append(account.getBankCode())
                    .append("，账号后4位：").append(tail4(account.getAccountNumber()))
                    .append("，币种：").append(account.getCurrency())
                    .append('\n');
        }
        sb.append("已有公司主体：");
        if (companies.isEmpty()) {
            sb.append("（暂无，全部需要建议新建）");
        } else {
            sb.append('\n');
            for (Company company : companies) {
                sb.append("- ").append(company.getName()).append('\n');
            }
        }
        sb.append("请输出 JSON 建议。");
        return sb.toString();
    }

    /**
     * 严格解析：剥 markdown 围栏后截取最外层大括号；accountId 必须命中输入集合
     * （防模型幻觉编造），重复 accountId 取首个，companyName 空白的行丢弃；
     * 容错单对象形态（模型漏掉 suggestions 包装时直接解析该对象）。
     */
    private List<AiCompanySuggestionResponse.Suggestion> parseSuggestions(String content, List<BankAccount> unfiled) {
        Map<Long, BankAccount> unfiledById = new LinkedHashMap<>();
        for (BankAccount account : unfiled) {
            unfiledById.put(account.getId(), account);
        }
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
            List<JsonNode> entries = new ArrayList<>();
            JsonNode items = json.path("suggestions");
            if (items.isArray()) {
                items.forEach(entries::add);
            } else if (json.hasNonNull("accountId") && json.hasNonNull("companyName")) {
                // 容错：模型把单条建议直接作为顶层对象返回（没有 suggestions 包装）。
                entries.add(json);
            } else {
                throw new BusinessException(502, "AI 归类建议缺少 suggestions 数组（模型未按约定 JSON 返回），可重试");
            }
            List<AiCompanySuggestionResponse.Suggestion> suggestions = new ArrayList<>();
            for (JsonNode item : entries) {
                JsonNode idNode = item.path("accountId");
                if (!idNode.canConvertToLong()) {
                    continue;
                }
                BankAccount account = unfiledById.get(idNode.asLong());
                String companyName = item.path("companyName").asText("");
                if (account == null || companyName.isBlank()) {
                    continue;
                }
                suggestions.add(new AiCompanySuggestionResponse.Suggestion(
                        account.getId(), account.getAccountName(), companyName.trim(),
                        item.path("confidence").isNumber() ? item.path("confidence").asDouble() : null,
                        item.path("reason").asText(null)));
                unfiledById.remove(account.getId());
            }
            if (suggestions.isEmpty()) {
                throw new BusinessException(502, "AI 归类建议为空或 accountId 全部无效，可重试");
            }
            return suggestions;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("AI 归类建议解析失败：{}", e.getMessage());
            throw new BusinessException(502, "AI 归类建议解析失败（响应非约定 JSON），可重试或手工归档");
        }
    }

    /** 模型响应片段：平化空白后截断，用于错误消息即时定位根因。 */
    private static String snippet(String content) {
        if (content == null || content.isBlank()) {
            return "（空响应）";
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= SNIPPET_LIMIT ? flat : flat.substring(0, SNIPPET_LIMIT) + "...";
    }

    private static String tail4(String accountNumber) {
        if (accountNumber == null || accountNumber.length() <= 4) {
            return "****";
        }
        return accountNumber.substring(accountNumber.length() - 4);
    }
}
