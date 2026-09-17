package com.finance.system.statement.voucherrule;

import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.Company;
import com.finance.system.domain.entity.StatementRecord;
import com.finance.system.statement.kingdee.KingdeeProperties;
import com.finance.system.statement.voucherrule.dto.KingdeeVoucherEntryDraft;
import com.finance.system.statement.voucherrule.dto.KingdeeVoucherRulePreview;
import com.finance.system.statement.voucherrule.dto.KingdeeVoucherRuleResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 规则引擎匹配服务（V34 WP-B）：银行流水 → 金蝶凭证规则。
 *
 * <p>实现设计文档《voucher-rule-engine-20260916.md》第二章匹配算法：</p>
 * <ol>
 *   <li><b>预过滤</b>：资金方向（EXPENSE/INCOME/ANY）、所属主体（scope_orgs=ALL 或含
 *   {@link KingdeeOrgResolver} 解析出的组织编码）、账户渠道（scope_bank_channels 空串=
 *   任意渠道，否则 CITIC/CMB 与 bank_account.bank_code 匹配）；</li>
 *   <li><b>条件求值</b>：logic=ALL 全部满足 / ANY 任一满足。SUMMARY 用流水摘要
 *   （CONTAINS/CONTAINS_ANY 同义：values 任一关键词为子串即命中）；COUNTERPARTY_NAME
 *   同上；IN_ORG_LIST = 对手方为集团内部主体（关键词表）；EMPLOYEE_NAME = 个人姓名
 *   启发式（无组织后缀、2-6 字）——金蝶 BD_Empinfo 对接为待财务确认项（设计文档四.8），
 *   启发式误判由人工确认队列兜底；</li>
 *   <li><b>优先级仲裁</b>：listEnabled 已按 priority→rule_no 稳定序；唯一命中 →
 *   AUTO_FILL 预填；多条命中 → CANDIDATES 全部交人工（社保/个税同附言冲突对的
 *   「金额门」由此自然实现——财务给出 amount_min/max 阈值后，金额过滤使剩余唯一命中
 *   自动预填，无需特判）；</li>
 *   <li><b>金额分摊</b>：FULL 全额；EQUAL 均分（分位四舍五入、尾差末行吸收）；MANUAL
 *   行金额留空、manual=true，推送前必须由确认页人工补齐；</li>
 *   <li><b>维度解析</b>：BANK_ACCOUNT 用全局默认 CN_BANKACNT 映射（per-account 映射
 *   为挂起项 pending-fixes）；BY_SUMMARY_BRANCH 按摘要分支选供应商；其余见
 *   {@link #resolveDimensionValue}。</li>
 * </ol>
 */
@Service
public class KingdeeVoucherMatchingService {

    static final String ST_AUTO_FILL = "AUTO_FILL";
    static final String ST_CANDIDATES = "CANDIDATES";
    static final String ST_UNMATCHED = "UNMATCHED";
    static final String ST_NOT_ELIGIBLE = "NOT_ELIGIBLE";

    private static final String SHARE_MANUAL = "MANUAL";
    private static final String BRANCH_DIM = "BY_SUMMARY_BRANCH";

    private final KingdeeVoucherRuleService ruleService;
    private final KingdeeOrgResolver orgResolver;
    private final KingdeeProperties kingdeeProps;

    public KingdeeVoucherMatchingService(KingdeeVoucherRuleService ruleService,
                                         KingdeeOrgResolver orgResolver,
                                         KingdeeProperties kingdeeProps) {
        this.ruleService = ruleService;
        this.orgResolver = orgResolver;
        this.kingdeeProps = kingdeeProps;
    }

    /**
     * Runs the rule engine for one standard statement. Company/account are optional
     * (legacy imported rows may miss either); a missing company only disables org-scoped
     * rules, a missing account only disables channel-scoped rules.
     */
    public KingdeeVoucherRulePreview preview(StatementRecord statement, BankAccount account, Company company) {
        if (statement.getAmount() == null
                || statement.getAmount().compareTo(BigDecimal.ZERO) <= 0
                || statement.getDirection() == null || statement.getDirection().isBlank()) {
            return new KingdeeVoucherRulePreview(statement.getId(), statement.getStatementNo(),
                    statement.getDirection(), statement.getAmount(), ST_NOT_ELIGIBLE,
                    "流水缺少金额或资金方向，无法制证", List.of());
        }
        String orgCode = company == null ? null : orgResolver.resolveOrgCode(company.getName());
        String bankCode = account == null ? null : normalize(account.getBankCode());

        List<KingdeeVoucherRuleResponse> rules = ruleService.listRules(true).stream()
                .filter(r -> directionMatches(r.direction(), statement.getDirection()))
                .filter(r -> orgMatches(r.scopeOrgs(), orgCode))
                .filter(r -> channelMatches(r.scopeBankChannels(), bankCode))
                .filter(r -> conditionsMatch(r.match(), statement))
                .filter(r -> amountGatePasses(r, statement.getAmount()))
                .toList();

        List<KingdeeVoucherRulePreview.Candidate> candidates = rules.stream()
                .map(r -> buildCandidate(r, statement, statement.getAmount(), account, orgCode))
                .toList();

        if (candidates.isEmpty()) {
            return new KingdeeVoucherRulePreview(statement.getId(), statement.getStatementNo(),
                    statement.getDirection(), statement.getAmount(), ST_UNMATCHED,
                    "无命中规则；可走 AI 制证入口兜底", List.of());
        }
        if (candidates.size() == 1) {
            return new KingdeeVoucherRulePreview(statement.getId(), statement.getStatementNo(),
                    statement.getDirection(), statement.getAmount(), ST_AUTO_FILL,
                    null, candidates);
        }
        return new KingdeeVoucherRulePreview(statement.getId(), statement.getStatementNo(),
                statement.getDirection(), statement.getAmount(), ST_CANDIDATES,
                "多条规则同时命中，需人工确认（同附言冲突对在财务给出金额阈值前始终人工）", candidates);
    }

    /** Builds the prefilled drafts for one rule against one statement (push path reuses this). */
    public KingdeeVoucherRulePreview.Candidate buildCandidate(KingdeeVoucherRuleResponse rule,
                                                              StatementRecord statement,
                                                              BigDecimal amount,
                                                              BankAccount account,
                                                              String orgCode) {
        List<KingdeeVoucherEntryDraft> debits = buildSides("DEBIT", rule.debitLines(), statement, amount, account, orgCode);
        List<KingdeeVoucherEntryDraft> credits = buildSides("CREDIT", rule.creditLines(), statement, amount, account, orgCode);
        boolean needManual = debits.stream().anyMatch(KingdeeVoucherEntryDraft::manual)
                || credits.stream().anyMatch(KingdeeVoucherEntryDraft::manual);
        KingdeeVoucherRulePreview.ExtraVoucherDraft extra = rule.extraVoucher() == null ? null
                : new KingdeeVoucherRulePreview.ExtraVoucherDraft(
                        rule.extraVoucher().note(),
                        buildSides("DEBIT", rule.extraVoucher().debitLines(), statement, amount, account, orgCode),
                        buildSides("CREDIT", rule.extraVoucher().creditLines(), statement, amount, account, orgCode));
        return new KingdeeVoucherRulePreview.Candidate(rule.ruleNo(), rule.businessType(),
                rule.category(), rule.priority(), needManual, debits, credits, extra);
    }

    private List<KingdeeVoucherEntryDraft> buildSides(String side,
                                                      List<KingdeeVoucherRuleResponse.LineTemplate> templates,
                                                      StatementRecord statement,
                                                      BigDecimal amount,
                                                      BankAccount account,
                                                      String orgCode) {
        if (templates == null || templates.isEmpty()) {
            return List.of();
        }
        List<BigDecimal> shares = allocate(amount, templates);
        List<KingdeeVoucherEntryDraft> drafts = new ArrayList<>(templates.size());
        for (int i = 0; i < templates.size(); i++) {
            KingdeeVoucherRuleResponse.LineTemplate t = templates.get(i);
            boolean manual = SHARE_MANUAL.equals(t.share());
            drafts.add(new KingdeeVoucherEntryDraft(
                    side, t.account(), t.name(), t.dimension(),
                    resolveDimensionValue(t, statement, account, orgCode),
                    manual ? null : shares.get(i), t.share(), manual));
        }
        return drafts;
    }

    /**
     * FULL = full amount on every line (templates are single-line in the seed);
     * EQUAL = equal split with 2-dp rounding, last line absorbs the remainder;
     * MANUAL lines get a placeholder share but the caller nulls the amount.
     */
    private static List<BigDecimal> allocate(BigDecimal amount, List<KingdeeVoucherRuleResponse.LineTemplate> templates) {
        int n = templates.size();
        List<BigDecimal> result = new ArrayList<>(n);
        boolean anyEqual = templates.stream().anyMatch(t -> "EQUAL".equals(t.share()));
        if (!anyEqual) {
            for (int i = 0; i < n; i++) {
                result.add(amount);
            }
            return result;
        }
        BigDecimal per = amount.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            if (i < n - 1) {
                result.add(per);
                allocated = allocated.add(per);
            } else {
                result.add(amount.subtract(allocated));
            }
        }
        return result;
    }

    private String resolveDimensionValue(KingdeeVoucherRuleResponse.LineTemplate t,
                                         StatementRecord statement,
                                         BankAccount account,
                                         String orgCode) {
        String dimension = t.dimension() == null ? "NONE" : t.dimension();
        return switch (dimension) {
            case "BANK_ACCOUNT" -> kingdeeProps.getDefaultBankAccountNumber();
            case "ORG" -> orgCode;
            case "SUPPLIER", "CUSTOMER", "COUNTERPARTY", "EMPLOYEE" -> statement.getCounterpartyName();
            case "FIXED" -> t.value();
            case BRANCH_DIM -> resolveSummaryBranch(t, statement.getSummary());
            default -> null; // NONE
        };
    }

    private static String resolveSummaryBranch(KingdeeVoucherRuleResponse.LineTemplate t, String summary) {
        if (t.branches() == null || summary == null) {
            return null;
        }
        return t.branches().stream()
                .filter(b -> b.whenSummaryContains() != null && summary.contains(b.whenSummaryContains()))
                .map(KingdeeVoucherRuleResponse.SummaryBranch::supplier)
                .findFirst().orElse(null);
    }

    private static boolean directionMatches(String ruleDirection, String statementDirection) {
        return ruleDirection == null || "ANY".equals(ruleDirection)
                || ruleDirection.equalsIgnoreCase(statementDirection);
    }

    private static boolean orgMatches(List<String> scopeOrgs, String orgCode) {
        if (scopeOrgs == null || scopeOrgs.isEmpty()) {
            return false; // malformed rule (column NOT NULL, parser guarantees non-null) — fail closed
        }
        if (scopeOrgs.contains("ALL")) {
            return true;
        }
        return orgCode != null && scopeOrgs.contains(orgCode);
    }

    private static boolean channelMatches(List<String> scopeChannels, String bankCode) {
        if (scopeChannels == null || scopeChannels.isEmpty()) {
            return true; // empty scope column = any channel
        }
        return bankCode != null && scopeChannels.stream().anyMatch(c -> normalize(c).equals(bankCode));
    }

    private boolean conditionsMatch(KingdeeVoucherRuleResponse.Match match, StatementRecord statement) {
        if (match == null || match.conditions() == null || match.conditions().isEmpty()) {
            return false; // malformed — fail closed
        }
        boolean logicAny = "ANY".equals(match.logic());
        List<Boolean> results = match.conditions().stream()
                .map(c -> conditionMatches(c, statement))
                .toList();
        return logicAny ? results.stream().anyMatch(Boolean::booleanValue)
                : results.stream().allMatch(Boolean::booleanValue);
    }

    private boolean conditionMatches(KingdeeVoucherRuleResponse.Condition c, StatementRecord statement) {
        if (c == null || c.field() == null || c.op() == null) {
            return false;
        }
        String haystack;
        if ("SUMMARY".equals(c.field())) {
            haystack = statement.getSummary() == null ? "" : statement.getSummary();
        } else if ("COUNTERPARTY_NAME".equals(c.field())) {
            haystack = statement.getCounterpartyName() == null ? "" : statement.getCounterpartyName();
        } else {
            return false; // unknown field — never auto-match
        }
        return switch (c.op()) {
            case "CONTAINS", "CONTAINS_ANY" -> c.values() != null && c.values().stream()
                    .anyMatch(v -> v != null && haystack.contains(v));
            case "IN_ORG_LIST" -> orgResolver.isGroupInternalName(haystack);
            case "EMPLOYEE_NAME" -> isPersonalName(haystack);
            default -> false; // unknown op — never auto-match
        };
    }

    private static boolean amountGatePasses(KingdeeVoucherRuleResponse rule, BigDecimal amount) {
        if (rule.amountMin() != null && amount.compareTo(rule.amountMin()) < 0) {
            return false;
        }
        return rule.amountMax() == null || amount.compareTo(rule.amountMax()) <= 0;
    }

    /**
     * Personal-name heuristic (design doc 四.8 pending finance confirmation): 2-6 chars,
     * CJK/· only, no organization suffix keyword. False positives land in the manual
     * confirmation queue anyway — this never auto-posts without human review.
     */
    static boolean isPersonalName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String trimmed = name.trim();
        if (trimmed.length() < 2 || trimmed.length() > 6) {
            return false;
        }
        String[] orgKeywords = {"公司", "银行", "中心", "有限", "集团", "厂", "店", "部", "所", "行",
                "院", "校", "队", "社", "馆", "网", "平台", "处", "局", "站", "厅"};
        for (String keyword : orgKeywords) {
            if (trimmed.contains(keyword)) {
                return false;
            }
        }
        return trimmed.chars().allMatch(ch ->
                (ch >= 0x4E00 && ch <= 0x9FFF) || ch == 0x00B7 || ch == 0x2022);
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase();
    }
}
