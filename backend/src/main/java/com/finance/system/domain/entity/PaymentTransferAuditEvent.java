package com.finance.system.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("payment_transfer_audit_event")
public class PaymentTransferAuditEvent {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long companyId;
    private Long paymentId;
    private String action;
    private String previousStatus;
    private String currentStatus;
    private Long operatorId;
    private String detail;
    private LocalDateTime createdAt;
    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getCompanyId() { return companyId; } public void setCompanyId(Long v) { companyId = v; }
    public Long getPaymentId() { return paymentId; } public void setPaymentId(Long v) { paymentId = v; }
    public String getAction() { return action; } public void setAction(String v) { action = v; }
    public String getPreviousStatus() { return previousStatus; } public void setPreviousStatus(String v) { previousStatus = v; }
    public String getCurrentStatus() { return currentStatus; } public void setCurrentStatus(String v) { currentStatus = v; }
    public Long getOperatorId() { return operatorId; } public void setOperatorId(Long v) { operatorId = v; }
    public String getDetail() { return detail; } public void setDetail(String v) { detail = v; }
    public LocalDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(LocalDateTime v) { createdAt = v; }
}
