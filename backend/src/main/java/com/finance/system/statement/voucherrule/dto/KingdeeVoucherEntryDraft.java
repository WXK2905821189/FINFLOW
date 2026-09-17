package com.finance.system.statement.voucherrule.dto;

import java.math.BigDecimal;

/**
 * 规则分录模板解析后的单行分录草稿（WP-B）：匹配服务产出、确认页（WP-C）展示与人工修正、
 * GL_VOUCHER payload builder（WP-D 面）消费的中间结构。
 *
 * @param side           DEBIT / CREDIT（借贷方向，来自规则的 debitLines/creditLines 归属）
 * @param account        金蝶科目编码（如 1002 / 6603.04）
 * @param accountName    科目名称（确认页展示用，来自 seed 模板）
 * @param dimension      维度来源类型（BANK_ACCOUNT/ORG/EMPLOYEE/SUPPLIER/CUSTOMER/
 *                       COUNTERPARTY/FIXED/BY_SUMMARY_BRANCH/NONE）
 * @param dimensionValue 解析后的维度值文本（供应商名/组织编码/固定值等）；NONE 时为 null
 * @param amount         金额；FULL/EQUAL 分摊后为具体值，MANUAL 行为 null（等待确认页人工填）
 * @param share          分摊策略（FULL/EQUAL/MANUAL）
 * @param manual         true = 该行金额必须由人工提供（确认页 override）才可推送
 */
public record KingdeeVoucherEntryDraft(
        String side,
        String account,
        String accountName,
        String dimension,
        String dimensionValue,
        BigDecimal amount,
        String share,
        boolean manual) {
}
