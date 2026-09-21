package com.finance.system.statement.voucherrule;

import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.KingdeeDimensionMapping;
import com.finance.system.domain.entity.KingdeeDimensionSlot;
import com.finance.system.domain.mapper.KingdeeDimensionMappingMapper;
import com.finance.system.domain.mapper.KingdeeDimensionSlotMapper;
import com.finance.system.statement.voucherrule.dto.KingdeeDimensionDtos.MappingResponse;
import com.finance.system.statement.voucherrule.dto.KingdeeDimensionDtos.MappingUpsertRequest;
import com.finance.system.statement.voucherrule.dto.KingdeeDimensionDtos.ResolvedDimension;
import com.finance.system.statement.voucherrule.dto.KingdeeDimensionDtos.SlotResponse;
import com.finance.system.statement.voucherrule.dto.KingdeeDimensionDtos.SlotUpsertRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 维度映射服务（V42）单元测试：槽位解析、值映射消歧、fail-visible 原因、批量导入幂等。
 */
class KingdeeDimensionMappingServiceTest {

    private final KingdeeDimensionSlotMapper slotMapper = mock(KingdeeDimensionSlotMapper.class);
    private final KingdeeDimensionMappingMapper mappingMapper = mock(KingdeeDimensionMappingMapper.class);
    private final KingdeeDimensionMappingService service =
            new KingdeeDimensionMappingService(slotMapper, mappingMapper);

    // ---------------- slotOf ----------------

    @Test
    void slotOfReturnsConfiguredSlot() {
        when(slotMapper.selectOne(any())).thenReturn(slot("BANK_ACCOUNT", "FF100002", true));
        assertEquals("FF100002", service.slotOf("BANK_ACCOUNT"));
    }

    @Test
    void slotOfReturnsNullWhenSlotNotYetCalibrated() {
        when(slotMapper.selectOne(any())).thenReturn(slot("SUPPLIER", null, true));
        assertNull(service.slotOf("SUPPLIER"), "槽位待报错驱动试出，未配置时不得猜");
    }

    @Test
    void slotOfRespectsDisabledFlag() {
        when(slotMapper.selectOne(any())).thenReturn(slot("EMPLOYEE", "FF100003", false));
        assertNull(service.slotOf("EMPLOYEE"));
    }

    // ---------------- resolveValue ----------------

    @Test
    void orgSpecificMappingWinsOverGeneric() {
        when(mappingMapper.selectList(any())).thenReturn(List.of(
                mapping(1L, "SUPPLIER", "某某供应商", "NAME", "VEN00001", ""),
                mapping(2L, "SUPPLIER", "某某供应商", "NAME", "VEN00410", "410")));
        assertEquals("VEN00410", service.resolveValue("SUPPLIER", "某某供应商", "410"),
                "组织精确映射优先于通用映射");
        assertEquals("VEN00001", service.resolveValue("SUPPLIER", "某某供应商", "999"),
                "其他组织下回落通用映射");
    }

    @Test
    void keywordMappingMatchesBySubstring() {
        when(mappingMapper.selectList(any())).thenReturn(List.of(
                mapping(1L, "BUSINESS_LINE", "图虫", "KEYWORD", "YX001", "")));
        assertEquals("YX001", service.resolveValue("BUSINESS_LINE", "跨行转账-图虫业务线分成", null));
        assertNull(service.resolveValue("BUSINESS_LINE", "无关摘要", null));
    }

    @Test
    void nameMappingIsCaseInsensitiveExact() {
        when(mappingMapper.selectList(any())).thenReturn(List.of(
                mapping(1L, "CUSTOMER", "Acme Ltd", "NAME", "KH001", "")));
        assertEquals("KH001", service.resolveValue("CUSTOMER", "acme ltd", null));
        assertNull(service.resolveValue("CUSTOMER", "Acme Ltd 北京", null), "NAME 语义为精确匹配");
    }

    @Test
    void resolveValueReturnsNullWhenNoMappingRows() {
        when(mappingMapper.selectList(any())).thenReturn(List.of());
        assertNull(service.resolveValue("EMPLOYEE", "张三", "410"));
    }

    // ---------------- resolve（fail-visible 原因） ----------------

    @Test
    void resolveExplainsMissingSlot() {
        when(slotMapper.selectOne(any())).thenReturn(slot("BUSINESS_LINE", null, true));
        ResolvedDimension resolved = service.resolve("BUSINESS_LINE", "图虫", "410");
        assertFalse(resolved.injectable());
        assertTrue(resolved.reason().contains("未配置弹性域槽位"), resolved.reason());
    }

    @Test
    void resolveExplainsMissingValueMapping() {
        when(slotMapper.selectOne(any())).thenReturn(slot("SUPPLIER", "FF100004", true));
        when(mappingMapper.selectList(any())).thenReturn(List.of());
        ResolvedDimension resolved = service.resolve("SUPPLIER", "某新供应商", "410");
        assertFalse(resolved.injectable());
        assertTrue(resolved.reason().contains("某新供应商"), "原因里要能看出是哪个来源值没映射");
        assertTrue(resolved.reason().contains("值映射"), resolved.reason());
    }

    @Test
    void resolveReturnsInjectableWhenBothReady() {
        when(slotMapper.selectOne(any())).thenReturn(slot("SUPPLIER", "FF100004", true));
        when(mappingMapper.selectList(any())).thenReturn(List.of(
                mapping(1L, "SUPPLIER", "某某供应商", "NAME", "VEN00001", "")));
        ResolvedDimension resolved = service.resolve("SUPPLIER", "某某供应商", "410");
        assertTrue(resolved.injectable());
        assertEquals("FF100004", resolved.slot());
        assertEquals("VEN00001", resolved.value());
        assertNull(resolved.reason());
    }

    // ---------------- CRUD ----------------

    @Test
    void createSlotRejectsDuplicateDimensionType() {
        when(slotMapper.selectCount(any())).thenReturn(1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createSlot(new SlotUpsertRequest("SUPPLIER", "供应商", null, "FF100004",
                        "BASE_DATA", true, null)));
        assertTrue(ex.getMessage().contains("已存在"));
    }

    @Test
    void createSlotNormalizesFieldsAndDefaultsEnabled() {
        when(slotMapper.selectCount(any())).thenReturn(0L);
        SlotResponse created = service.createSlot(new SlotUpsertRequest(
                "  CONTRACT  ", " 合同号 ", " ZDY0004 ", " FF100007 ", " CUSTOM ", null, null));
        assertEquals("CONTRACT", created.dimensionType());
        assertEquals("合同号", created.dimensionName());
        assertEquals("FF100007", created.slot());
        assertTrue(created.enabled(), "未显式传 enabled 时默认启用");
    }

    @Test
    void updateMappingRejectsIdentityChange() {
        KingdeeDimensionMapping existing = mapping(7L, "SUPPLIER", "旧名称", "NAME", "VEN1", "");
        when(mappingMapper.selectById(7L)).thenReturn(existing);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateMapping(7L, new MappingUpsertRequest(
                        "SUPPLIER", "新名称", "NAME", "VEN2", null, "", true, null)));
        assertTrue(ex.getMessage().contains("来源值不可修改"), ex.getMessage());
    }

    @Test
    void deleteMappingRejectsUnknownId() {
        when(mappingMapper.selectById(99L)).thenReturn(null);
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.deleteMapping(99L)).getCode());
    }

    // ---------------- batchUpsert ----------------

    @Test
    void batchUpsertUpdatesExistingAndInsertsNew() {
        when(mappingMapper.selectOne(any()))
                .thenReturn(mapping(5L, "SUPPLIER", "已存在供应商", "NAME", "VEN_OLD", ""))
                .thenReturn(null);
        when(mappingMapper.selectById(5L))
                .thenReturn(mapping(5L, "SUPPLIER", "已存在供应商", "NAME", "VEN_OLD", ""));

        int affected = service.batchUpsert(List.of(
                new MappingUpsertRequest("SUPPLIER", "已存在供应商", "NAME", "VEN_NEW", null, "", true, null),
                new MappingUpsertRequest("SUPPLIER", "新供应商", "NAME", "VEN_002", null, "", true, null)));

        assertEquals(2, affected);
        verify(mappingMapper, times(1)).insert(any(KingdeeDimensionMapping.class));
        verify(mappingMapper, times(1)).updateById(any(KingdeeDimensionMapping.class));
    }

    @Test
    void batchUpsertRejectsEmptyPayload() {
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.batchUpsert(List.of())).getCode());
    }

    @Test
    void batchUpsertTreatsNullOrgAsGeneric() {
        when(mappingMapper.selectOne(any())).thenReturn(null);
        service.batchUpsert(List.of(new MappingUpsertRequest(
                "SUPPLIER", "某供应商", "NAME", "VEN1", null, null, true, null)));
        // org_code 为 NOT NULL DEFAULT ''（唯一索引需要），null 须归一化为空串
        verify(mappingMapper).insert(any(KingdeeDimensionMapping.class));
    }

    // ---------------- fixtures ----------------

    private static KingdeeDimensionSlot slot(String type, String slotValue, boolean enabled) {
        KingdeeDimensionSlot entity = new KingdeeDimensionSlot();
        entity.setId(1L);
        entity.setDimensionType(type);
        entity.setSlot(slotValue);
        entity.setEnabled(enabled);
        return entity;
    }

    private static KingdeeDimensionMapping mapping(Long id, String type, String sourceKey,
                                                   String sourceKind, String value, String orgCode) {
        KingdeeDimensionMapping entity = new KingdeeDimensionMapping();
        entity.setId(id);
        entity.setDimensionType(type);
        entity.setSourceKey(sourceKey);
        entity.setSourceKind(sourceKind);
        entity.setKingdeeValue(value);
        entity.setOrgCode(orgCode);
        entity.setEnabled(true);
        return entity;
    }
}
