package com.finance.system.statement.voucherrule;

import com.finance.system.common.exception.BusinessException;
import com.finance.system.statement.kingdee.KingdeeProperties;
import com.finance.system.statement.kingdee.KingdeeVoucherGateway;
import com.finance.system.statement.voucherrule.dto.KingdeeVoucherEntryDraft;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GL_VOUCHER payload 构建（WP-B）：借贷平衡闸门、方向/金额/币别常量、凭证字/账簿配置。
 * 纯单测（无 Spring 上下文）；字段口径对照 2026-09-11 实账元数据快照。
 */
class KingdeeGlVoucherPayloadBuilderTest {

    private final KingdeeProperties props = new KingdeeProperties();
    private final KingdeeVoucherGateway gateway = mock(KingdeeVoucherGateway.class);
    private final KingdeeOrgResolver orgResolver = new KingdeeOrgResolver();
    private final KingdeeAccountCatalogService catalogService =
            new KingdeeAccountCatalogService(gateway, props);
    private final KingdeeGlVoucherPayloadBuilder builder =
            new KingdeeGlVoucherPayloadBuilder(props, new ObjectMapper(),
                    catalogService, orgResolver);

    private static final LocalDateTime T = LocalDateTime.parse("2026-09-16T10:15:00");

    @Test
    void buildsBalancedPayloadWithDebitCreditSemantics() throws Exception {
        String payload = builder.buildPayload("710", T, "银行手续费",
                List.of(line("DEBIT", "6603.04", "345.67")),
                List.of(line("CREDIT", "1002", "345.67")));

        JsonNode root = new ObjectMapper().readTree(payload);
        JsonNode model = root.path("Model");
        assertEquals("2026-09-16", model.path("FDate").asText());
        assertEquals("2026-09-16", model.path("FBUSDATE").asText());
        assertEquals("PRE001", model.path("FVOUCHERGROUPID").path("FNumber").asText(),
                "凭证字=记（PRE001），来自 kingdee.gl.voucher-group-number");
        assertEquals("710", model.path("FAccountBookID").path("FNumber").asText(),
                "账簿跟随组织（2026-09-22 定案）：org 710 → 账簿 710");
        assertEquals("710", model.path("FACCBOOKORGID").path("FNumber").asText(),
                "核算组织=规则主体解析出的组织编码");

        JsonNode entries = model.path("FEntity");
        assertEquals(2, entries.size());
        JsonNode debit = entries.get(0);
        assertEquals("6603.04", debit.path("FACCOUNTID").path("FNumber").asText());
        assertEquals(1, debit.path("FDC").asInt(), "借方 FDC=1");
        assertEquals(0, BigDecimal.valueOf(debit.path("FDEBIT").asDouble())
                .compareTo(new BigDecimal("345.67")));
        assertEquals(0, BigDecimal.valueOf(debit.path("FCREDIT").asDouble())
                .compareTo(BigDecimal.ZERO));
        assertEquals("银行手续费", debit.path("FEXPLANATION").asText());
        assertEquals("PRE001", debit.path("FCURRENCYID").path("FNumber").asText());
        assertEquals("HLTX01_SYS", debit.path("FEXCHANGERATETYPE").path("FNumber").asText());

        JsonNode credit = entries.get(1);
        assertEquals(2, credit.path("FDC").asInt(),
                "贷方 FDC=2（2026-09-21 真实账套实测保存成功；原 -1 未经验证）");
        assertEquals(0, BigDecimal.valueOf(credit.path("FCREDIT").asDouble())
                .compareTo(new BigDecimal("345.67")));
        assertEquals("1002", credit.path("FACCOUNTID").path("FNumber").asText());
    }

    @Test
    void unbalancedEntryIsRejected() {
        // 差 0.02 > 0.01 容差 → 拒绝（|差| ≤ 0.01 视为平衡，分位尾差场景）
        BusinessException ex = assertThrows(BusinessException.class, () -> builder.buildPayload(
                "400", T, "测试",
                List.of(line("DEBIT", "6603.04", "100.00")),
                List.of(line("CREDIT", "1002", "99.98"))));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("借贷不平衡"));
    }

    @Test
    void manualLineWithoutAmountIsRejected() {
        KingdeeVoucherEntryDraft manual = new KingdeeVoucherEntryDraft(
                "DEBIT", "2241.02.01", "其他应付款_社保_养老", "NONE", null, null, "MANUAL", true);
        BusinessException ex = assertThrows(BusinessException.class, () -> builder.buildPayload(
                "400", T, "扣国地税",
                List.of(manual, manual, manual, manual, manual, manual, manual, manual),
                List.of(line("CREDIT", "1002", "5600.00"))));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("未填金额的人工行"));
    }

    @Test
    void emptyDebitSideIsRejected() {
        BusinessException ex = assertThrows(BusinessException.class, () -> builder.buildPayload(
                "400", T, "测试", List.of(),
                List.of(line("CREDIT", "1002", "100.00"))));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("借方合计必须大于 0"));
    }

    private static KingdeeVoucherEntryDraft line(String side, String account, String amount) {
        return new KingdeeVoucherEntryDraft(side, account, "科目名", "NONE", null,
                new BigDecimal(amount), "FULL", false);
    }

    @Test
    void bankAccountDimensionUsesTwoLevelFDetailIdShape() throws Exception {
        // 2026-09-21 真实账套校准（凭证 16043 实测）：二层形态，内层键是带前缀的完整字段名。
        // 传裸槽位名 FF100002 或数组形态都会被金蝶拒绝（后者报 JSONArray→Dictionary 强转异常）。
        KingdeeVoucherEntryDraft bankLine = new KingdeeVoucherEntryDraft("CREDIT", "1002", "银行存款",
                "BANK_ACCOUNT", "11050160520009100036", new BigDecimal("345.67"), "FULL", false);
        String payload = builder.buildPayload("400", T, "收款",
                List.of(line("DEBIT", "6603.04", "345.67")), List.of(bankLine));

        JsonNode credit = new ObjectMapper().readTree(payload).path("Model").path("FEntity").get(1);
        JsonNode detail = credit.path("FDetailID");
        assertTrue(detail.isObject(), "FDetailID 必须是对象（数组会被金蝶拒绝）");
        assertEquals("11050160520009100036",
                detail.path("FDETAILID__FF100002").path("FNumber").asText(),
                "内层键 = FDETAILID__ + 槽位（默认 FF100002 = 银行账号 ZDY0001）");
    }

    @Test
    void dimensionSlotIsConfigurable() throws Exception {
        props.setGlBankDimensionSlot("FF100004");
        KingdeeVoucherEntryDraft bankLine = new KingdeeVoucherEntryDraft("CREDIT", "1002", "银行存款",
                "BANK_ACCOUNT", "11050160520009100036", new BigDecimal("10.00"), "FULL", false);
        String payload = builder.buildPayload("400", T, "收款",
                List.of(line("DEBIT", "6603.04", "10.00")), List.of(bankLine));

        JsonNode detail = new ObjectMapper().readTree(payload)
                .path("Model").path("FEntity").get(1).path("FDetailID");
        assertEquals("11050160520009100036",
                detail.path("FDETAILID__FF100004").path("FNumber").asText(),
                "槽位由 kingdee.gl.bank-dimension-slot 决定（账套级配置）");
    }

    @Test
    void noDimensionNodeIsEmittedWithoutDimensionValue() throws Exception {
        String payload = builder.buildPayload("400", T, "手续费",
                List.of(line("DEBIT", "6603.04", "10.00")),
                List.of(line("CREDIT", "1002", "10.00")));
        JsonNode credit = new ObjectMapper().readTree(payload).path("Model").path("FEntity").get(1);
        assertTrue(credit.path("FDetailID").isMissingNode(),
                "无维度值时不输出 FDetailID（避免空壳字段触发金蝶校验）");
    }

    // ---------------- 账簿跟随组织（2026-09-22 定案） ----------------

    @Test
    void accountBookFollowsOrgForNonSnowEntities() throws Exception {
        // 差分实验（2026-09-22）：「银行账号」维度档案必须属于账簿对应组织——
        // 图虫档案在账簿 400（雪云）下被金蝶判「不可用」，在账簿 410 下保存成功（16096）。
        String payload = builder.buildPayload("410", T, "服务费",
                List.of(line("DEBIT", "6602.11", "500.00")),
                List.of(line("CREDIT", "1002", "500.00")));
        JsonNode model = new ObjectMapper().readTree(payload).path("Model");
        assertEquals("410", model.path("FAccountBookID").path("FNumber").asText(),
                "图虫（org 410）必须推账簿 410（图虫账簿），不能落雪云账簿 400");
        assertEquals("410", model.path("FACCBOOKORGID").path("FNumber").asText());
    }

    @Test
    void accountBookFallsBackToConfiguredWhenOrgUnresolved() throws Exception {
        // 公司名未命中任何组织别名（orgCode=null）时保持旧行为：回退全局配置 400。
        String payload = builder.buildPayload(null, T, "测试",
                List.of(line("DEBIT", "6603.04", "10.00")),
                List.of(line("CREDIT", "1001", "10.00")));
        JsonNode model = new ObjectMapper().readTree(payload).path("Model");
        assertEquals("400", model.path("FAccountBookID").path("FNumber").asText(),
                "orgCode=null 回退 kingdee.gl.acctbook-number（全局默认）");
    }

    // ---------------- 多维度（V42，2026-09-21） ----------------

    @Test
    void multipleDimensionsShareOneDetailIdObject() throws Exception {
        // 图虫侧规则要求一条分录同时带「供应商 + 业务线」；两个键共用一个 FDetailID 对象。
        KingdeeVoucherEntryDraft multi = new KingdeeVoucherEntryDraft("DEBIT", "6602.11", "管理费用_福利费",
                "NONE", null, new BigDecimal("500.00"), "FULL", false,
                List.of(new KingdeeVoucherEntryDraft.DimensionValue("SUPPLIER", "FF100004", "VEN00511", null),
                        new KingdeeVoucherEntryDraft.DimensionValue("BUSINESS_LINE", "FF100008", "YX001", null)));
        String payload = builder.buildPayload("410", T, "服务费",
                List.of(multi), List.of(line("CREDIT", "1002", "500.00")));

        JsonNode detail = new ObjectMapper().readTree(payload)
                .path("Model").path("FEntity").get(0).path("FDetailID");
        assertTrue(detail.isObject());
        assertEquals("VEN00511", detail.path("FDETAILID__FF100004").path("FNumber").asText());
        assertEquals("YX001", detail.path("FDETAILID__FF100008").path("FNumber").asText());
    }

    @Test
    void unreadyDimensionBlocksPushWithActionableMessage() {
        KingdeeVoucherEntryDraft multi = new KingdeeVoucherEntryDraft("DEBIT", "6602.11", "管理费用",
                "NONE", null, new BigDecimal("500.00"), "FULL", false,
                List.of(new KingdeeVoucherEntryDraft.DimensionValue("SUPPLIER", null, null,
                        "维度 SUPPLIER 未配置弹性域槽位（在「维度映射 › 槽位配置」补）")));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> builder.buildPayload("410", T, "服务费",
                        List.of(multi), List.of(line("CREDIT", "1002", "500.00"))));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("SUPPLIER"), ex.getMessage());
        assertTrue(ex.getMessage().contains("维度映射"), "拒绝原因必须告诉用户去哪里补配置");
    }

    @Test
    void singleAndMultipleDimensionsCoexistOnOneEntry() throws Exception {
        // 单维度（BANK_ACCOUNT 走 kingdee.gl.bank-dimension-slot）+ 多维度列表共存
        KingdeeVoucherEntryDraft line = new KingdeeVoucherEntryDraft("CREDIT", "1002", "银行存款",
                "BANK_ACCOUNT", "11050160520009100036", new BigDecimal("88.00"), "FULL", false,
                List.of(new KingdeeVoucherEntryDraft.DimensionValue("BUSINESS_LINE", "FF100008", "YX002", null)));
        String payload = builder.buildPayload("400", T, "收款",
                List.of(line("DEBIT", "6603.04", "88.00")), List.of(line));

        JsonNode detail = new ObjectMapper().readTree(payload)
                .path("Model").path("FEntity").get(1).path("FDetailID");
        assertEquals("11050160520009100036", detail.path("FDETAILID__FF100002").path("FNumber").asText());
        assertEquals("YX002", detail.path("FDETAILID__FF100008").path("FNumber").asText());
    }
}
