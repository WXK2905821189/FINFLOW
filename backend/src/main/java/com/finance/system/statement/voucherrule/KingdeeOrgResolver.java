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
     * GL_VOUCHER 核算账簿（FAccountBookID）解析：**账簿号 = 核算组织号**（2026-09-22 实测定案）。
     *
     * <p>为什么不能继续用全局固定账簿：真实账套差分实验（2026-09-22，四点互证）证明
     * 「银行账号」维度值（CN_BANKACNT 档案）**必须属于账簿对应组织**，否则金蝶报
     * 「必录维度未录入或不可用：银行账号」——这正是图虫流水推账簿 400（雪云账簿）
     * 一直失败的根因：</p>
     * <ul>
     *   <li>账簿 400 + 雪云档案（org 400）→ ✅ 成功（单号 16094）；</li>
     *   <li>账簿 400 + 图虫档案（org 410）→ ❌（组织 400/410/411 三种 FACCBOOKORGID 全失败）；</li>
     *   <li>账簿 410（图虫账簿）+ 图虫档案 → ✅ 成功（单号 16096）；</li>
     *   <li>账簿 410 + 雪云档案 → ❌ 同报错（对称互证）。</li>
     * </ul>
     *
     * <p>同号依据：{@code BD_AccountBook} 全量实查（2026-09-22），本表 9 个组织编码
     * （300/400/410/411/420/421/710/720/900）均有同号账簿，且账簿名称与组织一一对应
     * （410=上海图虫、411=上海映脉、420=图虫浙江…）。若后续出现账簿号与组织号不一致的
     * 新主体，改本表即可。</p>
     *
     * @param orgCode 核算组织编码；null（公司名未命中别名）时回退
     *                {@code kingdee.gl.acctbook-number}（全局默认，保持旧行为）
     */
    public String resolveAcctbookCode(String orgCode, String defaultAcctbook) {
        if (orgCode == null || orgCode.isBlank()) {
            return defaultAcctbook;
        }
        // 组织编码同时就是账簿编码（实查核实）；无需查表，直接同值。
        return orgCode;
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
