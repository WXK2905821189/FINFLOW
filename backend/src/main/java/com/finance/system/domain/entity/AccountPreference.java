package com.finance.system.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 账号级界面偏好（V35）：Excel 表格内核的视图/列布局快照。
 *
 * <p>口径来自 {@code docs/ui-v34-demo.html} 的 V35 表格内核（口径③）：视图偏好存<strong>服务端账号级</strong>、
 * 跨设备一致，而不是本机 localStorage。粒度是 {@code (user_id, scope_key)}，scope_key 形如
 * {@code grid.balance} / {@code grid.statements}。</p>
 *
 * <p>{@code payload} 是前端构建的<strong>不透明 JSON 快照</strong>（可见列 / 列序 / 列宽 / 排序 /
 * 本页列头筛选 / 行密度 / 冻结列数 / 命名视图）。服务端只保证「合法 JSON + 长度上限」，不解释结构——
 * 结构演进属前端，避免每加一个字段就补一次迁移。</p>
 */
@TableName("account_preference")
public class AccountPreference {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 归属账号；偏好不跨账号共享。 */
    private Long userId;

    /** 网格实例标识（grid.balance / grid.statements …）。 */
    private String scopeKey;

    /** 前端不透明 JSON 快照。 */
    private String payload;

    private LocalDateTime createdAt;

    /** NULL = 从未编辑过（用于区分「没有偏好」与「偏好为空对象」）。 */
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getScopeKey() { return scopeKey; }
    public void setScopeKey(String scopeKey) { this.scopeKey = scopeKey; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
