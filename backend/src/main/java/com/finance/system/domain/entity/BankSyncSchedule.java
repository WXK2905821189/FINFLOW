package com.finance.system.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 用户可配置的定时同步计划（V25）：到点触发一轮全账户 T-1 同步。 */
@TableName("bank_sync_schedule")
public class BankSyncSchedule {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 执行时刻 HH:mm（24h）；整点/半点由 API 层拒绝（CMB 并发规范），不在 DB 约束。 */
    private String executeHhmm;

    private Boolean enabled;

    private Long createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getExecuteHhmm() { return executeHhmm; }
    public void setExecuteHhmm(String executeHhmm) { this.executeHhmm = executeHhmm; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
