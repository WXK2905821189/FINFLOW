package com.finance.system.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.AiPromptOverride;
import com.finance.system.domain.mapper.AiPromptOverrideMapper;
import com.finance.system.domain.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 提示词配置单测（V38，W9 需求 4）：覆盖读取口径（默认/覆盖/覆盖空白回落默认）、
 * upsert 校验（未登记 capability 400 / 空提示词 400 / 超长 400 / 先查后更）、
 * 重置（删行回落默认 / 无覆盖行 404）、目录完整性（三个能力全登记）。
 */
class AiPromptServiceTest {

    private AiPromptOverrideMapper mapper;
    private SysUserMapper userMapper;
    private AiPromptService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AiPromptOverrideMapper.class);
        userMapper = mock(SysUserMapper.class);
        service = new AiPromptService(mapper, userMapper);
    }

    @Test
    void resolveReturnsDefaultWhenNoOverride() {
        when(mapper.selectOne(any())).thenReturn(null);
        String resolved = service.resolve(AccountingSuggestionService.CAPABILITY);
        assertEquals(AccountingSuggestionService.SYSTEM_PROMPT, resolved);
    }

    @Test
    void resolveReturnsOverrideWhenPresent() {
        AiPromptOverride row = new AiPromptOverride();
        row.setCapability(AccountingSuggestionService.CAPABILITY);
        row.setSystemPrompt("自定义提示词：只输出 {\"ok\":true}");
        when(mapper.selectOne(any())).thenReturn(row);
        assertEquals("自定义提示词：只输出 {\"ok\":true}", service.resolve(AccountingSuggestionService.CAPABILITY));
    }

    @Test
    void resolveFallsBackToDefaultWhenOverrideBlank() {
        AiPromptOverride row = new AiPromptOverride();
        row.setCapability(AccountingSuggestionService.CAPABILITY);
        row.setSystemPrompt("   ");
        when(mapper.selectOne(any())).thenReturn(row);
        assertEquals(AccountingSuggestionService.SYSTEM_PROMPT, service.resolve(AccountingSuggestionService.CAPABILITY));
    }

    @Test
    void resolveRejectsUnregisteredCapability() {
        assertThrows(BusinessException.class, () -> service.resolve("definitely-not-a-capability"));
    }

    @Test
    void upsertRejectsUnregisteredCapability() {
        assertThrows(BusinessException.class, () -> service.upsert("nope", "提示词", 1L));
    }

    @Test
    void upsertRejectsBlankPrompt() {
        assertThrows(BusinessException.class, () -> service.upsert(AccountingSuggestionService.CAPABILITY, "  ", 1L));
        verify(mapper, never()).insert(any(AiPromptOverride.class));
    }

    @Test
    void upsertRejectsOverlongPrompt() {
        String huge = "长".repeat(20001);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.upsert(AccountingSuggestionService.CAPABILITY, huge, 1L));
        assertTrue(e.getMessage().contains("20000"));
    }

    @Test
    void upsertInsertsWhenNoRowAndUpdatesWhenRowExists() {
        when(mapper.selectOne(any())).thenReturn(null).thenReturn(overrideRow());
        when(mapper.selectList(any())).thenReturn(List.of(overrideRow()));
        service.upsert(AccountingSuggestionService.CAPABILITY, "覆盖一", 7L);
        verify(mapper).insert(any(AiPromptOverride.class));

        service.upsert(AccountingSuggestionService.CAPABILITY, "覆盖二", 7L);
        verify(mapper).updateById(any(AiPromptOverride.class));
    }

    @Test
    void resetDeletesOverrideRow() {
        when(mapper.selectOne(any())).thenReturn(overrideRow());
        service.reset(AccountingSuggestionService.CAPABILITY, 7L);
        // AiPromptService.reset 走 deleteById(id)（Serializable 重载），不是实体重载。
        verify(mapper).deleteById(1L);
    }

    @Test
    void resetIs404WhenNothingToReset() {
        when(mapper.selectOne(any())).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.reset(AccountingSuggestionService.CAPABILITY, 7L));
        assertEquals(404, e.getCode());
        verify(mapper, never()).deleteById(any(java.io.Serializable.class));
    }

    @Test
    void listCoversAllCatalogEntriesWithDefaultFlag() {
        when(mapper.selectList(any())).thenReturn(List.of());
        List<com.finance.system.ai.dto.AiPromptView> views = service.list();
        assertEquals(AiPromptCatalog.ALL.size(), views.size());
        assertTrue(views.stream().noneMatch(com.finance.system.ai.dto.AiPromptView::customized));
        assertEquals(AccountingSuggestionService.SYSTEM_PROMPT,
                views.get(0).effectivePrompt());
        assertFalse(views.get(0).customized());
    }

    private AiPromptOverride overrideRow() {
        AiPromptOverride row = new AiPromptOverride();
        row.setId(1L);
        row.setCapability(AccountingSuggestionService.CAPABILITY);
        row.setSystemPrompt("覆盖提示词");
        row.setUpdatedBy(7L);
        return row;
    }
}
