package com.finance.system.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("payment_transfer")
public class PaymentTransfer {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long companyId;
    private String paymentNo;
    private String idempotencyKey;
    private String requestId;
    private Long payerAccountId;
    private String bankCode;
    private String payeeName;
    private String payeeAccount;
    private String payeeAccountMasked;
    private String payeeBank;
    private BigDecimal amount;
    private String currency;
    private String remark;
    private String status;
    private Long createdBy;
    private Long approvedBy;
    private LocalDateTime approvedAt;
    private Long executedBy;
    private LocalDateTime executedAt;
    private String externalReference;
    private String externalStatus;
    private String errorMessage;
    private Long resolvedBy;
    private LocalDateTime resolvedAt;
    private String resolutionComment;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getCompanyId() { return companyId; } public void setCompanyId(Long v) { companyId = v; }
    public String getPaymentNo() { return paymentNo; } public void setPaymentNo(String v) { paymentNo = v; }
    public String getIdempotencyKey() { return idempotencyKey; } public void setIdempotencyKey(String v) { idempotencyKey = v; }
    public String getRequestId() { return requestId; } public void setRequestId(String v) { requestId = v; }
    public Long getPayerAccountId() { return payerAccountId; } public void setPayerAccountId(Long v) { payerAccountId = v; }
    public String getBankCode() { return bankCode; } public void setBankCode(String v) { bankCode = v; }
    public String getPayeeName() { return payeeName; } public void setPayeeName(String v) { payeeName = v; }
    public String getPayeeAccount() { return payeeAccount; } public void setPayeeAccount(String v) { payeeAccount = v; }
    public String getPayeeAccountMasked() { return payeeAccountMasked; } public void setPayeeAccountMasked(String v) { payeeAccountMasked = v; }
    public String getPayeeBank() { return payeeBank; } public void setPayeeBank(String v) { payeeBank = v; }
    public BigDecimal getAmount() { return amount; } public void setAmount(BigDecimal v) { amount = v; }
    public String getCurrency() { return currency; } public void setCurrency(String v) { currency = v; }
    public String getRemark() { return remark; } public void setRemark(String v) { remark = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public Long getCreatedBy() { return createdBy; } public void setCreatedBy(Long v) { createdBy = v; }
    public Long getApprovedBy() { return approvedBy; } public void setApprovedBy(Long v) { approvedBy = v; }
    public LocalDateTime getApprovedAt() { return approvedAt; } public void setApprovedAt(LocalDateTime v) { approvedAt = v; }
    public Long getExecutedBy() { return executedBy; } public void setExecutedBy(Long v) { executedBy = v; }
    public LocalDateTime getExecutedAt() { return executedAt; } public void setExecutedAt(LocalDateTime v) { executedAt = v; }
    public String getExternalReference() { return externalReference; } public void setExternalReference(String v) { externalReference = v; }
    public String getExternalStatus() { return externalStatus; } public void setExternalStatus(String v) { externalStatus = v; }
    public String getErrorMessage() { return errorMessage; } public void setErrorMessage(String v) { errorMessage = v; }
    public Long getResolvedBy() { return resolvedBy; } public void setResolvedBy(Long v) { resolvedBy = v; }
    public LocalDateTime getResolvedAt() { return resolvedAt; } public void setResolvedAt(LocalDateTime v) { resolvedAt = v; }
    public String getResolutionComment() { return resolutionComment; } public void setResolutionComment(String v) { resolutionComment = v; }
    public LocalDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(LocalDateTime v) { createdAt = v; }
    public LocalDateTime getUpdatedAt() { return updatedAt; } public void setUpdatedAt(LocalDateTime v) { updatedAt = v; }
}
