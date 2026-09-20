package com.finance.system.ai;

import com.finance.system.bank.AiCompanyClassifierService;
import com.finance.system.statement.voucherrule.KingdeeRuleImportService;

import java.util.List;
import java.util.function.Supplier;

/**
 * 可配置提示词的能力目录（V38，W9 需求 4）。
 *
 * <p>每个条目 = 一个可以在页面上编辑系统提示词的 AI 能力。目录是<strong>唯一登记处</strong>：
 * {@link AiPromptService} 的读取 / 保存 / 重置都以这里的 key 为准，未登记的 capability 一律 400。</p>
 *
 * <p>默认提示词用 {@code Supplier<String>} 挂到各 Service 的 public 常量上——改默认值只动
 * Service 源码，目录无需同步。新增一个可配置提示词的能力：Service 提供常量 + 这里加一个条目。</p>
 *
 * <p>注意：{@code self-test}（连通性探针）<strong>不</strong>进目录——它不是业务能力，
 * 提示词固定为一句 PONG，开放编辑没有意义。</p>
 */
public final class AiPromptCatalog {

    /** 一条可配置提示词能力的元数据。 */
    public record Entry(String capability, String name, String description, Supplier<String> defaultPrompt) {
    }

    public static final Entry ACCOUNTING_SUGGESTION = new Entry(
            AccountingSuggestionService.CAPABILITY,
            "智能入账建议",
            "流水查询页「AI 制证为草稿 / AI 制证并推送」与单条入账建议共用的系统提示词。",
            () -> AccountingSuggestionService.SYSTEM_PROMPT);

    public static final Entry COMPANY_CLASSIFICATION = new Entry(
            AiCompanyClassifierService.CAPABILITY,
            "公司主体归类",
            "账户档案页「AI 智能归类」的系统提示词（未归属账户 → 建议归入公司主体）。",
            () -> AiCompanyClassifierService.SYSTEM_PROMPT);

    public static final Entry RULE_IMPORT = new Entry(
            KingdeeRuleImportService.AUDIT_CAPABILITY,
            "规则导入映射",
            "规则中心 Excel 导入时 AI 列映射（一行原文 → 一条金蝶凭证规则）的系统提示词。",
            () -> KingdeeRuleImportService.SYSTEM_PROMPT);

    public static final List<Entry> ALL = List.of(
            ACCOUNTING_SUGGESTION, COMPANY_CLASSIFICATION, RULE_IMPORT);

    private AiPromptCatalog() {
    }

    /** 按 capability 取条目；未登记返回 null（调用方决定 400 语义）。 */
    public static Entry find(String capability) {
        if (capability == null) return null;
        return ALL.stream().filter(e -> e.capability().equals(capability)).findFirst().orElse(null);
    }
}
