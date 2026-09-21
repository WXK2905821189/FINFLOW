package com.finance.system.statement.voucherrule.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 规则分录模板解析后的单行分录草稿（WP-B）：匹配服务产出、确认页（WP-C）展示与人工修正、
 * GL_VOUCHER payload builder（WP-D 面）消费的中间结构。
 *
 * @param side           DEBIT / CREDIT（借贷方向，来自规则的 debitLines/creditLines 归属）
 * @param account        金蝶科目编码（如 1002 / 6603.04）
 * @param accountName    科目名称（确认页展示用，来自 seed 模板）
 * @param dimension      单维度来源类型（BANK_ACCOUNT/ORG/EMPLOYEE/SUPPLIER/CUSTOMER/
 *                       COUNTERPARTY/FIXED/BY_SUMMARY_BRANCH/NONE），向后兼容字段
 * @param dimensionValue 单维度解析后的值；NONE 时为 null
 * @param amount         金额；FULL/EQUAL 分摊后为具体值，MANUAL 行为 null（等待确认页人工填）
 * @param share          分摊策略（FULL/EQUAL/MANUAL）
 * @param manual         true = 该行金额必须由人工提供（确认页 override）才可推送
 * @param extraDimensions 多维度声明（2026-09-21 V42）：一条分录可同时带多个核算维度
 *                       （如供应商 + 部门 + 业务线）。元素值可能为空——表示规则要求该维度但
 *                       映射未配齐，推送时拒绝并给出补齐指引（fail-closed，不静默按空维度记账）。
 *                       与 {@code dimension/dimensionValue} 并存：单维度走原字段，多维度走本列表。
 */
public record KingdeeVoucherEntryDraft(
        String side,
        String account,
        String accountName,
        String dimension,
        String dimensionValue,
        BigDecimal amount,
        String share,
        boolean manual,
        List<DimensionValue> extraDimensions) {

    /** 兼容构造器：单维度场景（V34 起的旧签名，调用点无需改动）。 */
    public KingdeeVoucherEntryDraft(String side, String account, String accountName,
                                    String dimension, String dimensionValue,
                                    BigDecimal amount, String share, boolean manual) {
        this(side, account, accountName, dimension, dimensionValue, amount, share, manual, null);
    }

    /**
     * 一个待注入的核算维度。
     *
     * @param dimension 维度类型（SUPPLIER/EMPLOYEE/CUSTOMER/BANK_ACCOUNT/BUSINESS_LINE/ORG…）
     * @param slot      弹性域槽位键（来自 kingdee_dimension_slot；未配置时为 null）
     * @param value     金蝶档案编码（来自 kingdee_dimension_mapping；未映射时为 null）
     * @param note      未就绪原因（人话，推送拒绝时直接展示）
     */
    public record DimensionValue(String dimension, String slot, String value, String note) {

        public boolean injectable() {
            return slot != null && !slot.isBlank() && value != null && !value.isBlank();
        }
    }
}
