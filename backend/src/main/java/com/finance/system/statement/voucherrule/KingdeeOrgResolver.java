package com.finance.system.statement.voucherrule;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * FINFLOW 公司主体 → 金蝶核算组织编码解析（规则引擎 scope 预过滤与 ORG 维度共用）。
 *
 * <p>映射依据 2026-09-16 / 2026-09-21 两次 {@code ORG_Organizations} 实查（23 个组织）：</p>
 * <ul>
 *   <li>即设系：即设=300、雪云=400、长沙=710、广州=720、海南=900（2026-09-16 实查）；</li>
 *   <li>图虫系：图虫=410、映脉=411、浙江=420、浙江北分=421（2026-09-21 为图虫侧 19 条规则
 *   补入，实查确认这四个组织已存在）。</li>
 * </ul>
 *
 * <p><b>顺序即优先级，具体者必须在前</b>：匹配按公司全名「包含关键词」，先命中者胜。
 * 例：「北京雪云锐创科技有限公司长沙分公司」同时含「雪云」与「长沙」→ 长沙(710) 必须在前；
 * 同理「浙江北分」必须排在「浙江」之前。</p>
 *
 * <p>⚠️ <b>待确认</b>：若某主体全名同时含两个关键词（例如「浙江图虫网络科技有限公司」会同时
 * 命中「图虫」与「浙江」），当前按本表顺序取首个。接入真实主体前需核对 FINFLOW company.name
 * 实际写法（列在 toochong-rules-assessment-20260921.md 待办）。</p>
 *
 * <p>当前为内置常量（财务确认过的实查值）；若后续新增主体或编码调整，改本表即可，
 * 匹配服务与维度解析共用这一处。</p>
 */
@Component
public class KingdeeOrgResolver {

    /** Ordered aliases: branch/subsidiary keywords MUST precede the parent-company keyword. */
    private static final List<Map.Entry<String, String>> ALIASES = List.of(
            // --- 图虫系（2026-09-21 补入）---
            Map.entry("映脉", "411"),
            Map.entry("浙江北分", "421"),
            Map.entry("浙江", "420"),
            Map.entry("图虫", "410"),
            // --- 即设系 ---
            Map.entry("长沙", "710"),
            Map.entry("广州", "720"),
            Map.entry("海南", "900"),
            Map.entry("即设", "300"),
            Map.entry("雪云", "400"));

    private static final List<String> GROUP_KEYWORDS =
            ALIASES.stream().map(Map.Entry::getKey).toList();

    /**
     * Resolves the Kingdee org code for a FINFLOW company name; null when the name matches
     * no alias (rule scope then treats the statement as out-of-scope for org-restricted rules).
     */
    public String resolveOrgCode(String companyName) {
        if (companyName == null || companyName.isBlank()) {
            return null;
        }
        return ALIASES.stream()
                .filter(alias -> companyName.contains(alias.getKey()))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }

    /**
     * True when the counterparty name looks like an internal group entity
     * (rule 2 IN_ORG_LIST with the ORG_NAMES placeholder resolves to this list).
     */
    public boolean isGroupInternalName(String counterpartyName) {
        if (counterpartyName == null || counterpartyName.isBlank()) {
            return false;
        }
        return GROUP_KEYWORDS.stream().anyMatch(counterpartyName::contains);
    }
}
