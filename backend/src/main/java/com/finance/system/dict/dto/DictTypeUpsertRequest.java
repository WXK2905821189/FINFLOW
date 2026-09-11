package com.finance.system.dict.dto;

/**
 * 字典类型创建/更新请求。
 *
 * <p>{@code typeCode} 仅创建时必填且创建后不可改（消费方按 code 取数，
 * 改码等于隐性断链）；更新接口只接受名称/描述/状态。</p>
 */
public record DictTypeUpsertRequest(
        String typeCode,
        String name,
        String description,
        String status
) {
}
