package com.finance.system.statement.vouchergroup;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One row of the voucher center list (V34 ⑦ 凭证中心).
 *
 * <p>V34 ①「一组一凭证」终态是一组流水合并为一张金蝶出纳单据；当前规则引擎推送链路
 * （WP-B）是 1 笔流水 → 1 张 GL_VOUCHER，因此一期列表行 = 单笔流水的凭证记录，
 * {@code statementCount} 恒为 1。页面结构（凭证组号 / 来源流水列 / 单据详情页）已按
 * 凭证组语义设计，后续推送链路支持多笔合并时数据无需再改。</p>
 *
 * @param statementId    statement row id（详情单据页路由参数）
 * @param statementNo    来源流水号
 * @param voucherNo      金蝶凭证号（组号；未推送时为 null，前端显示 '--'）
 * @param businessDate   业务日期（流水交易时间）
 * @param companyName    公司主体名（bank_account.companyId 归属镜像口径）
 * @param bankAccount    银行账户显示名（银行 + 脱敏账号）
 * @param direction      收（INCOME）/ 付（EXPENSE）
 * @param amount         金额合计
 * @param currency       币种
 * @param summary        摘要（人工主摘要回写口径）
 * @param reviewStatus   复核状态（PENDING=待复核 / APPROVED=已通过 / REJECTED=已驳回）
 * @param pushStatus     推送状态（PUSHED/GL_PUSHED=已推送、FAILED/GL_FAILED=推送失败、null=未推送）
 * @param pushMessage    推送失败原因（可读摘要）
 * @param pushedAt       推送时间
 * @param statementCount 组内流水笔数（一期恒 1，见类注释）
 */
public record VoucherGroupResponse(
        Long statementId,
        String statementNo,
        String voucherNo,
        LocalDateTime businessDate,
        String companyName,
        String bankAccount,
        String direction,
        BigDecimal amount,
        String currency,
        String summary,
        String reviewStatus,
        String pushStatus,
        String pushMessage,
        LocalDateTime pushedAt,
        Integer statementCount
) {
}
