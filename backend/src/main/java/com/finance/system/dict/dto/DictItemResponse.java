package com.finance.system.dict.dto;

/** 字典项视图。extraJson 原样返回（写入时已校验为 JSON object），前端解析渲染。 */
public record DictItemResponse(
        Long id,
        Long typeId,
        String itemCode,
        String label,
        String extraJson,
        Integer sortNo,
        String status,
        String remark
) {
}
