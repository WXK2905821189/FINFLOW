package com.finance.system.statement.voucherrule.dto;

/**
 * 金蝶核算维度「槽位配置 + 值映射」的 API 载荷（V42，2026-09-21）。
 *
 * <p>放在一个文件里是为了让 Controller/Service 的签名保持成对可读
 * （项目既有风格：{@code KingdeeRuleGroupResponse.RuleUpsertRequest}）。</p>
 */
public final class KingdeeDimensionDtos {

    private KingdeeDimensionDtos() {
    }

    /** 槽位配置响应。 */
    public record SlotResponse(
            Long id,
            String dimensionType,
            String dimensionName,
            String dimensionCode,
            String slot,
            String dimensionKind,
            Boolean enabled,
            String remark) {
    }

    /** 槽位配置新增/修改请求；dimensionType 创建时必填，修改时以路径 id 为准。 */
    public record SlotUpsertRequest(
            String dimensionType,
            String dimensionName,
            String dimensionCode,
            String slot,
            String dimensionKind,
            Boolean enabled,
            String remark) {
    }

    /** 值映射响应。 */
    public record MappingResponse(
            Long id,
            String dimensionType,
            String sourceKey,
            String sourceKind,
            String kingdeeValue,
            String kingdeeName,
            String orgCode,
            Boolean enabled,
            String remark) {
    }

    /** 值映射新增/修改请求。 */
    public record MappingUpsertRequest(
            String dimensionType,
            String sourceKey,
            String sourceKind,
            String kingdeeValue,
            String kingdeeName,
            String orgCode,
            Boolean enabled,
            String remark) {
    }

    /**
     * 维度解析结果（引擎侧消费）：{@code value} 为空表示该维度最终不注入，
     * {@code reason} 给出人话原因（页面上直接展示，避免「静默少维度」）。
     */
    public record ResolvedDimension(
            String dimensionType,
            String slot,
            String value,
            String reason) {

        public boolean injectable() {
            return slot != null && !slot.isBlank() && value != null && !value.isBlank();
        }
    }
}
