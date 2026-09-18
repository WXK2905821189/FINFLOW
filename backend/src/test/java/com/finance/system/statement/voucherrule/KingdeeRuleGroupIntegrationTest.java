package com.finance.system.statement.voucherrule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.KingdeeRuleGroup;
import com.finance.system.domain.entity.KingdeeVoucherRule;
import com.finance.system.domain.mapper.KingdeeRuleGroupMapper;
import com.finance.system.domain.mapper.KingdeeVoucherRuleMapper;
import com.finance.system.statement.voucherrule.dto.KingdeeRuleGroupResponse;
import com.finance.system.statement.voucherrule.dto.KingdeeVoucherRuleResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W4 规则中心（2026-09-18）集成测试：分组 CRUD、规则 CRUD、Excel 导入两步链路。
 *
 * <p>共享 H2 注意：{@code KingdeeVoucherRuleServiceTest} 锚定 seed 22 条——本类创建的
 * 规则/分组一律用 {@code W4T-} 前缀并在 @AfterEach 物理清理，避免污染该断言。</p>
 *
 * <p>AI 侧为 fail-closed 断言：测试环境无可用 LLM 配置，preview 必须降级为
 * {@code aiMapped=false} 且不抛异常（AI 只加速不设卡的核心契约）。</p>
 */
@SpringBootTest
@ActiveProfiles("dev")
class KingdeeRuleGroupIntegrationTest {

    private static final String UNIQUE = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    @Autowired
    private KingdeeVoucherRuleService ruleService;
    @Autowired
    private KingdeeRuleImportService importService;
    @Autowired
    private KingdeeRuleGroupMapper groupMapper;
    @Autowired
    private KingdeeVoucherRuleMapper ruleMapper;

    @AfterEach
    void cleanup() {
        ruleMapper.delete(new LambdaQueryWrapper<KingdeeVoucherRule>()
                .likeRight(KingdeeVoucherRule::getBusinessType, "W4T-"));
        groupMapper.delete(new LambdaQueryWrapper<KingdeeRuleGroup>()
                .likeRight(KingdeeRuleGroup::getName, "W4T-"));
    }

    @Test
    void v37SeedGroupExistsAndHoldsSeedRules() {
        List<KingdeeRuleGroupResponse> groups = ruleService.listGroups();
        KingdeeRuleGroupResponse defaultGroup = groups.stream()
                .filter(g -> "财务默认规则".equals(g.name())).findFirst().orElse(null);
        assertNotNull(defaultGroup, "V37 必须 seed「财务默认规则」分组");
        assertEquals(22L, defaultGroup.ruleCount(), "22 条 seed 规则应全部归入默认分组");
    }

    @Test
    void groupCrudLifecycleWithGuards() {
        KingdeeRuleGroupResponse created = ruleService.createGroup(
                new KingdeeRuleGroupResponse.UpsertRequest("W4T-组-" + UNIQUE, "测试组", 5));
        assertNotNull(created.id());

        // 重名 409
        assertThrows(BusinessException.class, () -> ruleService.createGroup(
                new KingdeeRuleGroupResponse.UpsertRequest("W4T-组-" + UNIQUE, null, null)));

        // 更名 + 计数
        KingdeeRuleGroupResponse renamed = ruleService.updateGroup(created.id(),
                new KingdeeRuleGroupResponse.UpsertRequest("W4T-组改-" + UNIQUE, "改描述", null));
        assertEquals("W4T-组改-" + UNIQUE, renamed.name());
        assertEquals("改描述", renamed.description());
        assertEquals(0L, renamed.ruleCount());

        // 空组可删
        ruleService.deleteGroup(created.id());
        assertNull(groupMapper.selectById(created.id()));

        // 空名 400
        assertThrows(BusinessException.class, () -> ruleService.createGroup(
                new KingdeeRuleGroupResponse.UpsertRequest("  ", null, null)));
    }

    @Test
    void deleteNonEmptyGroupBlocked409() {
        KingdeeRuleGroupResponse group = ruleService.createGroup(
                new KingdeeRuleGroupResponse.UpsertRequest("W4T-非空-" + UNIQUE, null, 0));
        ruleService.createRule(upsert("W4T-规则甲", group.id()));
        BusinessException e = assertThrows(BusinessException.class, () -> ruleService.deleteGroup(group.id()));
        assertEquals(409, e.getCode());
    }

    @Test
    void ruleCrudWithGroupAndRuleNoGuards() {
        KingdeeRuleGroupResponse group = ruleService.createGroup(
                new KingdeeRuleGroupResponse.UpsertRequest("W4T-CRUD-" + UNIQUE, null, 0));

        // 不传 ruleNo → 自动 max+1
        KingdeeVoucherRuleResponse created = ruleService.createRule(upsert("W4T-规则乙", group.id()));
        assertNotNull(created.id());
        assertNotNull(created.ruleNo(), "未指定 ruleNo 时必须自动分配");
        assertEquals(group.id(), created.groupId());
        assertEquals("W4T-CRUD-" + UNIQUE, created.groupName(), "响应必须带分组名（join）");
        assertEquals(999, created.priority());

        // 指定冲突 ruleNo → 409（撞 seed 规则 1）
        KingdeeRuleGroupResponse.RuleUpsertRequest clash = new KingdeeRuleGroupResponse.RuleUpsertRequest(
                1, "W4T-规则丙", "W4T-大类", 10, null, null, "EXPENSE", null, null,
                match(), debits(), credits(), null, true, null, group.id());
        BusinessException e = assertThrows(BusinessException.class, () -> ruleService.createRule(clash));
        assertEquals(409, e.getCode());

        // 更新：改大类 + 停用
        KingdeeRuleGroupResponse.RuleUpsertRequest patch = new KingdeeRuleGroupResponse.RuleUpsertRequest(
                null, "W4T-规则乙", "W4T-大类改", 999, null, null, "INCOME", null, null,
                match(), debits(), credits(), null, false, null, null);
        KingdeeVoucherRuleResponse updated = ruleService.updateRule(created.id(), patch);
        assertEquals("W4T-大类改", updated.category());
        assertFalse(updated.enabled());
        assertNull(updated.groupId(), "groupId=null 表示移出分组");

        // 校验门：缺匹配条件 400
        KingdeeRuleGroupResponse.RuleUpsertRequest noMatch = new KingdeeRuleGroupResponse.RuleUpsertRequest(
                null, "W4T-规则丁", "W4T-大类", 1, null, null, "EXPENSE", null, null,
                new KingdeeVoucherRuleResponse.Match("ALL", List.of()), debits(), credits(), null, true, null, null);
        assertThrows(BusinessException.class, () -> ruleService.createRule(noMatch));

        // 校验门：方向非法 400
        KingdeeRuleGroupResponse.RuleUpsertRequest badDirection = new KingdeeRuleGroupResponse.RuleUpsertRequest(
                null, "W4T-规则戊", "W4T-大类", 1, null, null, "SIDEWAYS", null, null,
                match(), debits(), credits(), null, true, null, null);
        assertThrows(BusinessException.class, () -> ruleService.createRule(badDirection));
    }

    @Test
    void confirmImportAssignsDefaultGroupAndAutoRuleNo() {
        KingdeeRuleGroupResponse group = ruleService.createGroup(
                new KingdeeRuleGroupResponse.UpsertRequest("W4T-导入-" + UNIQUE, null, 0));
        List<KingdeeRuleGroupResponse.RuleUpsertRequest> rows = List.of(
                upsert("W4T-导入行1", null), upsert("W4T-导入行2", null));
        List<KingdeeVoucherRuleResponse> created = ruleService.confirmImport(rows, group.id());

        assertEquals(2, created.size());
        assertTrue(created.stream().allMatch(r -> group.id().equals(r.groupId())),
                "confirm 必须把 defaultGroupId 落到每行");
        assertTrue(created.stream().allMatch(r -> r.ruleNo() != null), "导入行自动分配 ruleNo");
    }

    @Test
    void previewParsesXlsxAndFailsClosedWithoutAi() throws Exception {
        // 1. 模板可下载且为 xlsx（zip 魔数 PK）
        byte[] template = importService.template();
        assertEquals('P', template[0]);
        assertEquals('K', template[1]);

        // 2. 用 POI 现造一个含表头 + 2 数据行的 xlsx 上传
        byte[] xlsx = buildTestXlsx();
        MockMultipartFile file = new MockMultipartFile("file", "rules-" + UNIQUE + ".xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx);
        KingdeeRuleGroupResponse.ImportPreviewResponse preview = importService.preview(file, 1L);

        assertEquals(2, preview.totalRows());
        assertEquals(2, preview.rows().size());
        // 测试环境 AI fail-closed：全部行 aiMapped=false 且带原因（不抛异常中断）
        assertTrue(preview.rows().stream().noneMatch(KingdeeRuleGroupResponse.ImportPreviewRow::aiMapped),
                "无 AI 配置时不得有 aiMapped=true 行");
        assertTrue(preview.rows().stream().allMatch(r -> r.aiNote() != null && !r.aiNote().isBlank()));
        assertTrue(preview.aiSummary().contains("AI 未参与"), "摘要须说明 AI 降级");
        // 原文单元格矩阵完整带回（人工修正依赖原文）
        assertEquals("租金", preview.rows().get(0).sourceCells().get(1));
    }

    @Test
    void previewRejectsNonXlsxAndEmptyFile() {
        MockMultipartFile csv = new MockMultipartFile("file", "rules.csv", "text/csv", "a,b".getBytes());
        assertThrows(BusinessException.class, () -> importService.preview(csv, 1L));
        MockMultipartFile empty = new MockMultipartFile("file", "empty.xlsx",
                "application/octet-stream", new byte[0]);
        assertThrows(BusinessException.class, () -> importService.preview(empty, 1L));
    }

    // ---------------- fixture ----------------

    private KingdeeRuleGroupResponse.RuleUpsertRequest upsert(String businessType, Long groupId) {
        return new KingdeeRuleGroupResponse.RuleUpsertRequest(
                null, businessType, "W4T-大类", 999, null, null, "EXPENSE", null, null,
                match(), debits(), credits(), null, true, null, groupId);
    }

    private KingdeeVoucherRuleResponse.Match match() {
        return new KingdeeVoucherRuleResponse.Match("ALL",
                List.of(new KingdeeVoucherRuleResponse.Condition("SUMMARY", "CONTAINS", List.of("W4T关键词"))));
    }

    private List<KingdeeVoucherRuleResponse.LineTemplate> debits() {
        return List.of(new KingdeeVoucherRuleResponse.LineTemplate(
                "660203", "租赁费", "COUNTERPARTY", null, null, "FULL"));
    }

    private List<KingdeeVoucherRuleResponse.LineTemplate> credits() {
        return List.of(new KingdeeVoucherRuleResponse.LineTemplate(
                "100201", "银行存款", "BANK_ACCOUNT", null, null, "FULL"));
    }

    private byte[] buildTestXlsx() throws Exception {
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             var out = new java.io.ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("规则");
            var header = sheet.createRow(0);
            String[] headers = {"规则名称", "业务类型", "大类", "方向", "摘要关键词",
                    "对方单位关键词", "借方科目编码", "借方科目名称", "贷方科目编码", "贷方科目名称", "备注"};
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            String[][] rows = {
                    {"办公室租金", "租金", "租赁费", "EXPENSE", "租金", "", "660203", "租赁费", "100201", "银行存款", "W4T示例1"},
                    {"收货款", "货款", "销售收入", "INCOME", "", "货款", "100201", "银行存款", "600101", "主营业务收入", "W4T示例2"},
            };
            for (int r = 0; r < rows.length; r++) {
                var row = sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) {
                    row.createCell(c).setCellValue(rows[r][c]);
                }
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
