package com.finance.system.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("bank_account")
public class BankAccount {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long companyId;
    private String bankCode;
    private String accountName;
    private String accountNumber;
    private String currency;
    private BigDecimal availableBalance;
    private String status;
    /** 制证模式（V31）：KINGDEE_AUTO=可走 AI 制证推送金蝶链路；MANUAL=纯人工制证，数据仅留系统。 */
    private String accountingMode;
    /**
     * 软删除标记（V32）：1=已删除。物理 DELETE 被 statement/balance/sync_log/payment 的
     * NOT NULL 外键阻断且财务原数据必须留存，因此档案移除走逻辑删除——@TableLogic 使所有
     * MyBatis-Plus 查询（档案板/下拉/数据查询/调度器/测试连接/金蝶推送）自动过滤已删账户。
     */
    @TableLogic
    private Integer deleted = 0;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCompanyId() { return companyId; }
    public void setCompanyId(Long companyId) { this.companyId = companyId; }
    public String getBankCode() { return bankCode; }
    public void setBankCode(String bankCode) { this.bankCode = bankCode; }
    public String getAccountName() { return accountName; }
    public void setAccountName(String accountName) { this.accountName = accountName; }
    public String getAccountNumber() { return accountNumber; }
    public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public BigDecimal getAvailableBalance() { return availableBalance; }
    public void setAvailableBalance(BigDecimal availableBalance) { this.availableBalance = availableBalance; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAccountingMode() { return accountingMode; }
    public void setAccountingMode(String accountingMode) { this.accountingMode = accountingMode; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
