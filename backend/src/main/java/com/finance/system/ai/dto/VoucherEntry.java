package com.finance.system.ai.dto;

import java.math.BigDecimal;

/**
 * 凭证分录（V33 凭证草稿详情）：AI 预填的单条分录。
 *
 * <p>方向约定与金蝶记账一致：收入（CREDIT 收款）= 借 银行存款 / 贷 业务科目；
 * 支出（DEBIT 付款）= 借 业务科目 / 贷 银行存款。subjectCode 为 AI 推断的科目编码，
 * 不确定时为 null（以金蝶账套科目表为准，人工复核时修正）。</p>
 *
 * @param direction   DEBIT（借方）/ CREDIT（贷方）
 * @param confidence  AI 对该行科目的置信度自评 0.0~1.0
 */
public record VoucherEntry(
        String summary,
        String subjectCode,
        String subjectName,
        String direction,
        BigDecimal amount,
        Double confidence) {

    public static final String DIRECTION_DEBIT = "DEBIT";
    public static final String DIRECTION_CREDIT = "CREDIT";
}
