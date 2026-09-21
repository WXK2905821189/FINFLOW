package com.finance.system.statement.dto;

/**
 * 一键 AI 制证的提交响应：两种模式返回形态不同，前端按 {@code async} 分支。
 *
 * <ul>
 *   <li>DRAFT：{@code async=true} + {@code jobId}/{@code totalCount}，任务在后台跑，
 *       进度与逐行结果去「凭证中心」轮询（GET /bank-data/ai-voucher-jobs/latest）；</li>
 *   <li>PUSH：{@code async=false} + 同步结果 {@code result}（用户要立即看到推送结果）。</li>
 * </ul>
 */
public record AiVoucherSubmitResponse(
        boolean async,
        Long jobId,
        Integer totalCount,
        AiVoucherBatchResponse result
) {

    public static AiVoucherSubmitResponse async(Long jobId, Integer totalCount) {
        return new AiVoucherSubmitResponse(true, jobId, totalCount, null);
    }

    public static AiVoucherSubmitResponse sync(AiVoucherBatchResponse result) {
        return new AiVoucherSubmitResponse(false, null, result.totalCount(), result);
    }
}
