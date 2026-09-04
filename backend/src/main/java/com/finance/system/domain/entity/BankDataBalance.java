package com.finance.system.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("bank_data_balance")
public class BankDataBalance {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long companyId;
    private Long taskId;
    private Long rawMessageId;
    private Long bankAccountId;
    private String bankRequestNo;
    private BigDecimal availableBalance;
    private String currency;
    private LocalDateTime asOfTime;
    private String validationStatus;
    private String validationMessage;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCompanyId() { return companyId; }
    public void setCompanyId(Long companyId) { this.companyId = companyId; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getRawMessageId() { return rawMessageId; }
    public void setRawMessageId(Long rawMessageId) { this.rawMessageId = rawMessageId; }
    public Long getBankAccountId() { return bankAccountId; }
    public void setBankAccountId(Long bankAccountId) { this.bankAccountId = bankAccountId; }
    public String getBankRequestNo() { return bankRequestNo; }
    public void setBankRequestNo(String bankRequestNo) { this.bankRequestNo = bankRequestNo; }
    public BigDecimal getAvailableBalance() { return availableBalance; }
    public void setAvailableBalance(BigDecimal availableBalance) { this.availableBalance = availableBalance; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public LocalDateTime getAsOfTime() { return asOfTime; }
    public void setAsOfTime(LocalDateTime asOfTime) { this.asOfTime = asOfTime; }
    public String getValidationStatus() { return validationStatus; }
    public void setValidationStatus(String validationStatus) { this.validationStatus = validationStatus; }
    public String getValidationMessage() { return validationMessage; }
    public void setValidationMessage(String validationMessage) { this.validationMessage = validationMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
