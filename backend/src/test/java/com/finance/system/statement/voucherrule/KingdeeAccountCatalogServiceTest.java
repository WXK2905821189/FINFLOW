package com.finance.system.statement.voucherrule;

import com.finance.system.common.exception.BusinessException;
import com.finance.system.statement.kingdee.KingdeeProperties;
import com.finance.system.statement.kingdee.KingdeeVoucherGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 账套科目目录服务：科目校验（防静默记错科目）+ 银行账号维度需求判定 + 降级口径。
 * 真实背景：账套里 2232 是「应付股利」而 AI 把它当「应付账款」用（2026-09-21 实测）。
 */
class KingdeeAccountCatalogServiceTest {

    private KingdeeVoucherGateway gateway;
    private KingdeeProperties props;
    private KingdeeAccountCatalogService service;

    private static KingdeeVoucherGateway.KingdeeAccountRef ref(String number, String name, String dimension) {
        return new KingdeeVoucherGateway.KingdeeAccountRef(number, name, dimension);
    }

    @BeforeEach
    void setUp() {
        gateway = mock(KingdeeVoucherGateway.class);
        props = new KingdeeProperties();
        when(gateway.queryAccountCatalog()).thenReturn(List.of(
                ref("1001", "库存现金", null),
                ref("1002", "银行存款", "ZDY0001"),
                ref("1122.01", "外部往来", null),
                ref("2232", "应付股利", null)));
        service = new KingdeeAccountCatalogService(gateway, props);
    }

    @Test
    void matchedCodeAndNamePasses() {
        KingdeeAccountCatalogService.AccountCheck check = service.check("1002", "银行存款");
        assertFalse(check.catalogUnavailable());
        assertEquals("银行存款", check.name());
    }

    @Test
    void catalogIsCachedWithinTtl() {
        service.check("1002", "银行存款");
        service.check("1001", "库存现金");
        service.requiresBankDimension("1002");
        verify(gateway, times(1)).queryAccountCatalog();
    }

    @Test
    void unknownCodeIsRejectedWithActionableMessage() {
        BusinessException ex = assertThrows(BusinessException.class, () -> service.check("9999", "不存在"));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("9999"));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    @Test
    void nameMismatchIsRejectedToPreventSilentMisposting() {
        // AI 把 2232 当「应付账款」用，账套实为「应付股利」→ 必须拒绝，而不是记错账
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.check("2232", "应付账款"));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("应付股利"), "错误信息要给出账套实际名称");
        assertTrue(ex.getMessage().contains("应付账款"), "并指出本地名称，便于对照修正");
    }

    @Test
    void blankLocalNameSkipsNameCheckButKeepsExistenceCheck() {
        KingdeeAccountCatalogService.AccountCheck check = service.check("2232", null);
        assertEquals("应付股利", check.name());
        assertFalse(check.catalogUnavailable());
    }

    @Test
    void bankDimensionRequirementComesFromCatalog() {
        assertTrue(service.requiresBankDimension("1002"));
        assertFalse(service.requiresBankDimension("1001"));
        assertFalse(service.requiresBankDimension("2232"));
        assertFalse(service.requiresBankDimension("不存在"));
    }

    @Test
    void catalogFailureDegradesToUnavailableInsteadOfBlockingPushes() {
        when(gateway.queryAccountCatalog()).thenThrow(
                new BusinessException(502, "Kingdee bill query failed: read timed out"));
        KingdeeAccountCatalogService degraded = new KingdeeAccountCatalogService(gateway, props);

        assertFalse(degraded.isCatalogAvailable());
        KingdeeAccountCatalogService.AccountCheck check = degraded.check("1002", "银行存款");
        assertTrue(check.catalogUnavailable(), "目录不可用时降级放行（不因校验基建故障整体阻断制证）");
        assertFalse(degraded.requiresBankDimension("1002"), "无法判定维度需求时不注入，交由金蝶报错校准");
    }

    @Test
    void uniqueNameResolvesToCode() {
        KingdeeAccountCatalogService.NameResolution hit = service.resolveByName("库存现金");
        assertTrue(hit.unique());
        assertEquals("1001", hit.code());
    }

    @Test
    void duplicateNameIsReportedAsAmbiguousWithCandidates() {
        when(gateway.queryAccountCatalog()).thenReturn(List.of(
                ref("1122.01", "外部往来", null),
                ref("1123.01", "外部往来", null),
                ref("2241.06", "外部往来", null)));
        KingdeeAccountCatalogService ambiguous = new KingdeeAccountCatalogService(gateway, props);

        KingdeeAccountCatalogService.NameResolution result = ambiguous.resolveByName("外部往来");
        assertFalse(result.unique());
        assertTrue(result.ambiguous());
        assertEquals(3, result.candidates().size(), "重名必须把候选全部交出来供人工指定");
    }

    @Test
    void unknownNameResolvesToNothing() {
        KingdeeAccountCatalogService.NameResolution miss = service.resolveByName("不存在的科目");
        assertFalse(miss.unique());
        assertFalse(miss.ambiguous());
    }
}
