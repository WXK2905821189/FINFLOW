package com.finance.system.statement.voucherrule;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * FINFLOW 公司主体 → 金蝶核算组织编码解析（规则引擎 scope 预过滤与 ORG 维度共用）。
 *
 * <p>映射依据 2026-09-16 ORG_Organizations 实查（23 个组织），与
 * {@code voucher-rules-seed-20260916.json} 的 orgAliasMap 一致：即设=300、雪云=400、
 * 海南=900、长沙=710、广州=720。匹配按公司全名包含关键词；<b>分公司关键词必须先于
 * 主公司</b>（"北京雪云锐创科技有限公司长沙分公司" 同时含「雪云」与「长沙」）。</p>
 *
 * <p>当前为内置常量（财务确认过的实查值）；若后续新增主体或编码调整，改本表即可，
 * 匹配服务与维度解析共用这一处。</p>
 */
@Component
public class KingdeeOrgResolver {

    /** Ordered aliases: branch/subsidiary keywords MUST precede the parent-company keyword. */
    private static final List<Map.Entry<String, String>> ALIASES = List.of(
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
