package com.finance.system.statement.voucherrule;

import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.KingdeeVoucherRule;
import com.finance.system.statement.voucherrule.dto.KingdeeVoucherRuleResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 凭证规则地基（V34 WP-A）：V34 迁移种子 22 条（财务映射表 2026-09-16）的完整性与
 * JSON 列结构化解析。断言直接锚定 seed 原文——种子变更必须同步迁移与本文档。
 */
@SpringBootTest
@ActiveProfiles("dev")
class KingdeeVoucherRuleServiceTest {

    @Autowired
    private KingdeeVoucherRuleService ruleService;

    @Test
    void seedLoadsAll22RulesEnabled() {
        List<KingdeeVoucherRule> rules = ruleService.listEnabled();
        assertEquals(22, rules.size(), "V34 seed 应导入财务映射表全部 22 条规则");
        assertTrue(rules.stream().allMatch(r -> Boolean.TRUE.equals(r.getEnabled())));
    }

    @Test
    void rulesSortedByPriorityThenRuleNo() {
        List<KingdeeVoucherRule> rules = ruleService.listEnabled();
        for (int i = 1; i < rules.size(); i++) {
            int prevPriority = rules.get(i - 1).getPriority();
            int currPriority = rules.get(i).getPriority();
            assertTrue(prevPriority <= currPriority, "priority 必须升序");
            if (prevPriority == currPriority) {
                assertTrue(rules.get(i - 1).getRuleNo() < rules.get(i).getRuleNo(),
                        "同优先级内 rule_no 必须升序（稳定序）");
            }
        }
        // 优先级分布：社保/个税(10,11)=15 精确规则在前，宽泛兜底(2,3,21)=30 在后
        assertEquals(15, rules.get(0).getPriority());
        assertEquals(10, rules.get(0).getRuleNo());
        assertEquals(30, rules.get(rules.size() - 1).getPriority());
    }

    @Test
    void ruleNoRangeCoversFinanceSheet() {
        List<KingdeeVoucherRule> rules = ruleService.listEnabled();
        List<Integer> ruleNos = rules.stream().map(KingdeeVoucherRule::getRuleNo).sorted().toList();
        for (int i = 1; i <= 22; i++) {
            assertTrue(ruleNos.contains(i), "缺少规则 " + i);
        }
    }

    @Test
    void rule1HasExtraVoucherWithEmployeeDimension() {
        KingdeeVoucherRuleResponse rule = ruleService.getByRuleNo(1);
        assertNotNull(rule.extraVoucher(), "规则 1 必须带「直接确认费用」第二张凭证模板");
        assertEquals(1, rule.extraVoucher().debitLines().size());
        assertEquals("6602.11", rule.extraVoucher().debitLines().get(0).account());
        assertEquals("EMPLOYEE", rule.extraVoucher().creditLines().get(0).dimension());
        // 主凭证：借 2241.04 员工往来 / 贷 1002 银行存款（银行维度）
        assertEquals("2241.04", rule.debitLines().get(0).account());
        assertEquals("BANK_ACCOUNT", rule.creditLines().get(0).dimension());
        // 匹配条件：摘要含「报销」+ 对手方为员工姓名
        assertEquals("ALL", rule.match().logic());
        assertEquals(2, rule.match().conditions().size());
        assertEquals("SUMMARY", rule.match().conditions().get(0).field());
        assertEquals(List.of("报销"), rule.match().conditions().get(0).values());
        assertEquals("EMPLOYEE_NAME", rule.match().conditions().get(1).op());
    }

    @Test
    void rule10SocialInsuranceEightManualDebitLines() {
        KingdeeVoucherRuleResponse rule = ruleService.getByRuleNo(10);
        assertEquals(8, rule.debitLines().size(), "社保规则 8 个借方科目按上月工资表人工拆分");
        assertTrue(rule.debitLines().stream().allMatch(l -> "MANUAL".equals(l.share())));
        assertEquals("2241.02.01", rule.debitLines().get(0).account());
        assertEquals("2211.02.04", rule.debitLines().get(7).account());
        assertEquals("NONE", rule.debitLines().get(0).dimension());
        assertEquals("FULL", rule.creditLines().get(0).share());
    }

    @Test
    void rule18TencentSummaryBranchesParse() {
        KingdeeVoucherRuleResponse rule = ruleService.getByRuleNo(18);
        KingdeeVoucherRuleResponse.LineTemplate debit = rule.debitLines().get(0);
        assertEquals("BY_SUMMARY_BRANCH", debit.dimension());
        assertNotNull(debit.branches());
        assertEquals(2, debit.branches().size());
        assertEquals("图虫账号", debit.branches().get(0).whenSummaryContains());
        assertEquals("腾讯云计算（北京）有限责任公司-图虫", debit.branches().get(0).supplier());
        assertEquals("即时账号", debit.branches().get(1).whenSummaryContains());
        assertEquals("腾讯云计算（北京）有限责任公司-即时", debit.branches().get(1).supplier());
    }

    @Test
    void scopeParsingCoversAllAndChannelSemantics() {
        // 规则 2：全部主体、任意渠道、收入方向借贷对调语义存于 remark
        KingdeeVoucherRuleResponse rule2 = ruleService.getByRuleNo(2);
        assertEquals(List.of("ALL"), rule2.scopeOrgs());
        assertEquals(List.of(), rule2.scopeBankChannels(), "空渠道串 = 任意渠道");
        assertEquals("ANY", rule2.direction());
        // 规则 8：三主体 × 双渠道
        KingdeeVoucherRuleResponse rule8 = ruleService.getByRuleNo(8);
        assertEquals(List.of("300", "710", "720"), rule8.scopeOrgs());
        assertEquals(List.of("CITIC", "CMB"), rule8.scopeBankChannels());
        // 规则 4：无 extraVoucher、无 remark
        KingdeeVoucherRuleResponse rule4 = ruleService.getByRuleNo(4);
        assertNull(rule4.extraVoucher());
        assertNull(rule4.remark());
        assertNull(rule4.amountMin());
    }

    @Test
    void getByRuleNoUnknownReturns404() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> ruleService.getByRuleNo(999));
        assertEquals(404, ex.getCode());
    }
}
