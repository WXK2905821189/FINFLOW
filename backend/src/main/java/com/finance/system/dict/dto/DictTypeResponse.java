package com.finance.system.dict.dto;

/** 字典类型视图（管理端）。itemCount 供列表页展示规模。 */
public record DictTypeResponse(
        Long id,
        String typeCode,
        String name,
        String description,
        String status,
        long itemCount,
        String createdAt
) {
}
