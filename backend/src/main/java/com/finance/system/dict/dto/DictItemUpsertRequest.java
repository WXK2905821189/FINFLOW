package com.finance.system.dict.dto;

/** 字典项创建/更新请求。extraJson 可空；非空必须是 JSON object（键值扩展属性）。 */
public record DictItemUpsertRequest(
        String itemCode,
        String label,
        String extraJson,
        Integer sortNo,
        String status,
        String remark
) {
}
