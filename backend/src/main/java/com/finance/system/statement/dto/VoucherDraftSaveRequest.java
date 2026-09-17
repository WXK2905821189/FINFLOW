package com.finance.system.statement.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * PUT /statements/{id}/voucher-draft 请求体（V33 凭证草稿详情）：
 * 人工在金蝶式单据页修正后的分录与主摘要。
 *
 * @param summary 主摘要（可空=沿用 AI 建议）；非空时服务端同步回写 statement.summary，
 *                使金蝶推送单据的 FREMARK/FCOMMENT 带上人工修正后的摘要
 */
public record VoucherDraftSaveRequest(List<EntryInput> entries, String summary) {

    /**
     * 单条分录输入。direction 必须为 DEBIT/CREDIT；amount 为正数；
     * 保存时服务端校验借贷平衡（±0.01）。
     */
    public record EntryInput(
            String summary,
            String subjectCode,
            String subjectName,
            String direction,
            BigDecimal amount,
            Double confidence) {
    }
}
