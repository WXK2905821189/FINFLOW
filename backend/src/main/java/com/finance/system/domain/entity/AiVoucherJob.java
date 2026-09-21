package com.finance.system.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 一键 AI 制证异步任务（V40，2026-09-21）。
 *
 * <p>DRAFT 模式从同步改为后台任务：提交即返回任务号，服务端逐行跑
 * 「转入 → AI 建议 → 落草稿」，计数与逐行结果回写本行，页面在「凭证中心」轮询展示。
 * PUSH 模式仍是同步链路（用户要立即看到推送结果），不落本表。</p>
 *
 * <p>{@code rowsJson} 是 {@code AiVoucherRowResult} 数组的 JSON（含失败 message，
 * 前端据此渲染失败诊断）；写入前按 {@code AiVoucherJobService} 的上限截断。</p>
 */
@TableName("ai_voucher_job")
public class AiVoucherJob {

    /** 任务进行中。 */
    public static final String STATUS_RUNNING = "RUNNING";
    /** 任务完成（含部分行失败——行级失败不影响任务状态）。 */
    public static final String STATUS_COMPLETED = "COMPLETED";
    /** 任务整体失败（线程内未捕获异常），message 记原因。 */
    public static final String STATUS_FAILED = "FAILED";

    /** 生成制证草稿（异步任务）。 */
    public static final String MODE_DRAFT = "DRAFT";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long companyId;

    private String mode;

    private String status;

    /** 转入批次号（多公司拆批时记第一批，与同步链路口径一致）。 */
    private String batchNo;

    private Integer totalCount;

    private Integer draftCount;

    private Integer pushedCount;

    private Integer alreadyCount;

    private Integer skippedCount;

    private Integer failedCount;

    private String rowsJson;

    private String message;

    private Long createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime finishedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getCompanyId() { return companyId; }
    public void setCompanyId(Long companyId) { this.companyId = companyId; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }

    public Integer getTotalCount() { return totalCount; }
    public void setTotalCount(Integer totalCount) { this.totalCount = totalCount; }

    public Integer getDraftCount() { return draftCount; }
    public void setDraftCount(Integer draftCount) { this.draftCount = draftCount; }

    public Integer getPushedCount() { return pushedCount; }
    public void setPushedCount(Integer pushedCount) { this.pushedCount = pushedCount; }

    public Integer getAlreadyCount() { return alreadyCount; }
    public void setAlreadyCount(Integer alreadyCount) { this.alreadyCount = alreadyCount; }

    public Integer getSkippedCount() { return skippedCount; }
    public void setSkippedCount(Integer skippedCount) { this.skippedCount = skippedCount; }

    public Integer getFailedCount() { return failedCount; }
    public void setFailedCount(Integer failedCount) { this.failedCount = failedCount; }

    public String getRowsJson() { return rowsJson; }
    public void setRowsJson(String rowsJson) { this.rowsJson = rowsJson; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
}
