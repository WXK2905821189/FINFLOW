package com.finance.system.statement.voucherrule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.KingdeeVoucherRule;
import com.finance.system.domain.mapper.KingdeeVoucherRuleMapper;
import com.finance.system.statement.voucherrule.dto.KingdeeVoucherRuleResponse;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * 金蝶凭证规则引擎 — 规则供数地基（V34 WP-A）。
 *
 * <p>职责：规则行的查询与 JSON 列结构化。匹配算法（预过滤/条件求值/优先级仲裁/金额门）
 * 属 WP-B（规则解析服务），本服务只提供稳定的数据访问面：</p>
 * <ul>
 *   <li>{@link #listEnabled()}：匹配引擎的取数入口，enabled=1 按 priority 升序、
 *   rule_no 升序稳定排序（同优先级多命中不自动定案，交人工确认队列——WP-B 职责）；</li>
 *   <li>{@link #listRules(Boolean)}：HTTP 只读查询（voucher:push 持有者可见规则全貌，
 *   制证确认页展示推断依据）；</li>
 *   <li>JSON 解析失败按数据损坏处理（500），不静默跳过——规则 seed 由迁移导入受版本控制，
 *   出现损坏说明手工改动入库，必须显式失败。</li>
 * </ul>
 */
@Service
public class KingdeeVoucherRuleService {

    private final KingdeeVoucherRuleMapper ruleMapper;
    private final ObjectMapper objectMapper;

    public KingdeeVoucherRuleService(KingdeeVoucherRuleMapper ruleMapper, ObjectMapper objectMapper) {
        this.ruleMapper = ruleMapper;
        this.objectMapper = objectMapper;
    }

    /** 匹配引擎取数入口：启用中的规则，priority 升序 → rule_no 升序稳定排序。 */
    public List<KingdeeVoucherRule> listEnabled() {
        return ruleMapper.selectList(new LambdaQueryWrapper<KingdeeVoucherRule>()
                .eq(KingdeeVoucherRule::getEnabled, true)
                .orderByAsc(KingdeeVoucherRule::getPriority)
                .orderByAsc(KingdeeVoucherRule::getRuleNo));
    }

    /**
     * 只读查询（HTTP）：enabledOnly=null 查全部；true/false 按启用状态过滤。
     * 排序同 {@link #listEnabled()}。
     */
    public List<KingdeeVoucherRuleResponse> listRules(Boolean enabledOnly) {
        LambdaQueryWrapper<KingdeeVoucherRule> wrapper = new LambdaQueryWrapper<KingdeeVoucherRule>()
                .orderByAsc(KingdeeVoucherRule::getPriority)
                .orderByAsc(KingdeeVoucherRule::getRuleNo);
        if (enabledOnly != null) {
            wrapper.eq(KingdeeVoucherRule::getEnabled, enabledOnly);
        }
        return ruleMapper.selectList(wrapper).stream().map(this::toResponse).toList();
    }

    /** 单条规则结构化（WP-B 按 ruleNo 复查时可用）。 */
    public KingdeeVoucherRuleResponse getByRuleNo(int ruleNo) {
        KingdeeVoucherRule rule = ruleMapper.selectOne(new LambdaQueryWrapper<KingdeeVoucherRule>()
                .eq(KingdeeVoucherRule::getRuleNo, ruleNo));
        if (rule == null) {
            throw new BusinessException(404, "凭证规则不存在: ruleNo=" + ruleNo);
        }
        return toResponse(rule);
    }

    private KingdeeVoucherRuleResponse toResponse(KingdeeVoucherRule rule) {
        try {
            List<String> orgs = splitCsv(rule.getScopeOrgs());
            List<String> channels = splitCsv(rule.getScopeBankChannels());
            KingdeeVoucherRuleResponse.Match match =
                    objectMapper.readValue(rule.getMatchJson(), KingdeeVoucherRuleResponse.Match.class);
            List<KingdeeVoucherRuleResponse.LineTemplate> debitLines = objectMapper.readValue(
                    rule.getDebitLinesJson(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class,
                            KingdeeVoucherRuleResponse.LineTemplate.class));
            List<KingdeeVoucherRuleResponse.LineTemplate> creditLines = objectMapper.readValue(
                    rule.getCreditLinesJson(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class,
                            KingdeeVoucherRuleResponse.LineTemplate.class));
            KingdeeVoucherRuleResponse.ExtraVoucher extraVoucher = rule.getExtraVoucherJson() == null
                    ? null
                    : objectMapper.readValue(rule.getExtraVoucherJson(),
                            KingdeeVoucherRuleResponse.ExtraVoucher.class);
            return new KingdeeVoucherRuleResponse(
                    rule.getId(), rule.getRuleNo(), rule.getBusinessType(), rule.getCategory(),
                    rule.getPriority(), orgs, channels, rule.getDirection(),
                    rule.getAmountMin(), rule.getAmountMax(), match, debitLines, creditLines,
                    extraVoucher, Boolean.TRUE.equals(rule.getEnabled()), rule.getRemark());
        } catch (Exception e) {
            throw new BusinessException(500,
                    "凭证规则 JSON 损坏 (ruleNo=" + rule.getRuleNo() + "): " + e.getMessage());
        }
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
