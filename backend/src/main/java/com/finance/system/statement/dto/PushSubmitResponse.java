package com.finance.system.statement.dto;

/**
 * 一键推送提交响应：异步任务提交即返回，进度与逐行结果去「凭证中心」轮询。
 *
 * @param async      恒为 true（W16-A1 起推送一律后台化）
 * @param jobId      任务号
 * @param totalCount 提交行数
 */
public record PushSubmitResponse(
        boolean async,
        Long jobId,
        Integer totalCount
) {

    public static PushSubmitResponse of(Long jobId, Integer totalCount) {
        return new PushSubmitResponse(true, jobId, totalCount);
    }
}
