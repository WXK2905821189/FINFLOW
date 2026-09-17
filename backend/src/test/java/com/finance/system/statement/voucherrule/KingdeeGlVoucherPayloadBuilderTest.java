package com.finance.system.statement.voucherrule;

import com.finance.system.common.exception.BusinessException;
import com.finance.system.statement.kingdee.KingdeeProperties;
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

/**
 * GL_VOUCHER payload 构建（WP-B）：借贷平衡闸门、方向/金额/币别常量、凭证字/账簿配置。
 * 纯单测（无 Spring 上下文）；字段口径对照 2026-09-11 实账元数据快照。
 */
class KingdeeGlVoucherPayloadBuilderTest {

    private final KingdeeProperties props = new KingdeeProperties();
    private final KingdeeGlVoucherPayloadBuilder builder =
            new KingdeeGlVoucherPayloadBuilder(props, new ObjectMapper());

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
        assertEquals("400", model.path("FAccountBookID").path("FNumber").asText());
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
        assertEquals(-1, credit.path("FDC").asInt(), "贷方 FDC=-1（REAL 首推校准点）");
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
}
