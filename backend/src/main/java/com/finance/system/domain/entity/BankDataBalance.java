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
    /** 联机余额 onlblv - the account's actual funds. */
    private BigDecimal onlineBalance;
    /** 冻结余额 hldblv - judicial + bank holds combined. */
    private BigDecimal frozenBalance;
    /** 上日余额 accblv - online balance minus today's financial transactions. */
    private BigDecimal previousDayBalance;
    /** 币种代码 ccynbr as the bank codes it, not an ISO code. */
    private String vendorCurrencyCode;
    /** 分行号 bbknbr. */
    private String branchCode;
    /** 银行侧账号 accnbr. */
    private String bankAccountNo;
    /** 银行侧户名 accnam. */
    private String bankAccountName;
    /** 科目 accitm. */
    private String accountItem;
    /** 客户关系号 relnbr. */
    private String customerRelationNo;
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
    public BigDecimal getOnlineBalance() { return onlineBalance; }
    public void setOnlineBalance(BigDecimal onlineBalance) { this.onlineBalance = onlineBalance; }
    public BigDecimal getFrozenBalance() { return frozenBalance; }
    public void setFrozenBalance(BigDecimal frozenBalance) { this.frozenBalance = frozenBalance; }
    public BigDecimal getPreviousDayBalance() { return previousDayBalance; }
    public void setPreviousDayBalance(BigDecimal previousDayBalance) { this.previousDayBalance = previousDayBalance; }
    public String getVendorCurrencyCode() { return vendorCurrencyCode; }
    public void setVendorCurrencyCode(String vendorCurrencyCode) { this.vendorCurrencyCode = vendorCurrencyCode; }
    public String getBranchCode() { return branchCode; }
    public void setBranchCode(String branchCode) { this.branchCode = branchCode; }
    public String getBankAccountNo() { return bankAccountNo; }
    public void setBankAccountNo(String bankAccountNo) { this.bankAccountNo = bankAccountNo; }
    public String getBankAccountName() { return bankAccountName; }
    public void setBankAccountName(String bankAccountName) { this.bankAccountName = bankAccountName; }
    public String getAccountItem() { return accountItem; }
    public void setAccountItem(String accountItem) { this.accountItem = accountItem; }
    public String getCustomerRelationNo() { return customerRelationNo; }
    public void setCustomerRelationNo(String customerRelationNo) { this.customerRelationNo = customerRelationNo; }
    public String getValidationStatus() { return validationStatus; }
    public void setValidationStatus(String validationStatus) { this.validationStatus = validationStatus; }
    public String getValidationMessage() { return validationMessage; }
    public void setValidationMessage(String validationMessage) { this.validationMessage = validationMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
