package com.finance.system.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 一键推送至金蝶异步任务（W16-A1，2026-09-22）。
 *
 * <p>流水查询页单一「一键推送至金蝶」按钮的后台任务承载：勾选流水 → 跑规则中心匹配 →
 * 仅唯一命中（AUTO_FILL）且无需人工金额的行自动组装推送金蝶草稿；其余落为「问题凭证」。
 * 计数与逐行结果回写本行，页面在「凭证中心」轮询展示。</p>
 *
 * <p>{@code rowsJson} 是 {@code PushRowResult} 数组的 JSON（含 outcome 与失败原因，
 * 前端据此渲染诊断）；写入前按 {@code BankPushJobService} 的上限截断。</p>
 */
@TableName("bank_push_job")
public class BankPushJob {

    /** 任务进行中。 */
    public static final String STATUS_RUNNING = "RUNNING";
    /** 任务完成（含部分行失败——行级失败不影响任务状态）。 */
    public static final String STATUS_COMPLETED = "COMPLETED";
    /** 任务整体失败（线程内未捕获异常），message 记原因。 */
    public static final String STATUS_FAILED = "FAILED";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long companyId;

    private String status;

    /** 转入批次号（多公司拆批时记第一批，与同步链路口径一致）。 */
    private String batchNo;

    private Integer totalCount;

    /** 本次自动推送成功数（唯一命中且无需人工金额）。 */
    private Integer pushedCount;

    /** 问题凭证数（多候选/未命中/需人工金额/不可制证/推送失败）。 */
    private Integer problemCount;

    /** 跳过数（纯人工制证账户 / 无公司归属或越权 / 已人工驳回）。 */
    private Integer skippedCount;

    /** 幂等跳过数（此前已推送成功——已推送是终局，重跑不动它）。 */
    private Integer alreadyCount;

    private String rowsJson;

    private String message;

    private Long createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime finishedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getCompanyId() { return companyId; }
    public void setCompanyId(Long companyId) { this.companyId = companyId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }

    public Integer getTotalCount() { return totalCount; }
    public void setTotalCount(Integer totalCount) { this.totalCount = totalCount; }

    public Integer getPushedCount() { return pushedCount; }
    public void setPushedCount(Integer pushedCount) { this.pushedCount = pushedCount; }

    public Integer getProblemCount() { return problemCount; }
    public void setProblemCount(Integer problemCount) { this.problemCount = problemCount; }

    public Integer getSkippedCount() { return skippedCount; }
    public void setSkippedCount(Integer skippedCount) { this.skippedCount = skippedCount; }

    public Integer getAlreadyCount() { return alreadyCount; }
    public void setAlreadyCount(Integer alreadyCount) { this.alreadyCount = alreadyCount; }

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
