package com.finance.system.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One bank statement row.
 *
 * <p>The first block of fields is the FINFLOW accounting projection (unsigned {@code amount}
 * + {@code INCOME}/{@code EXPENSE} direction). The second block, added in migration V18,
 * carries the bank's own fields verbatim so a reviewer can compare a row against the bank's
 * statement export field by field. They are nullable and never derived: the adapter fills
 * them straight from the response, and adapters without vendor detail leave them null.</p>
 */
@TableName("bank_data_statement")
public class BankDataStatement {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long companyId;
    private Long taskId;
    private Long rawMessageId;
    private Long bankAccountId;
    private String bankRequestNo;
    private String statementNo;
    private LocalDateTime transactionTime;
    private String direction;
    private BigDecimal amount;
    private String currency;
    private String counterpartyName;
    private String counterpartyAccountMasked;
    private String summary;
    private String validationStatus;
    private String validationMessage;
    private LocalDateTime createdAt;

    // ---- V18: bank-native fields (CMB trsQryByBreakPoint Z2), stored verbatim ----
    /** 账号 acctNbr as the bank reports it. */
    private String bankAccountNo;
    /** 起息日 valueDate. */
    private LocalDate valueDate;
    /** 借贷码 loanCode: C 贷方 / D 借方. */
    private String loanCode;
    /** 带符号交易金额 transAmount：借方为负、贷方为正。 */
    private BigDecimal signedAmount;
    /** 交易类型 textCode. */
    private String textCode;
    /** 票据号 billNumber. */
    private String billNumber;
    /** 你方摘要 remarkTextClt. */
    private String remarkTextClt;
    /** 冲账标志 reversalFlag: * 冲账 / X 补账. */
    private String reversalFlag;
    /** 每笔后余额 acctOnlineBal. */
    private BigDecimal acctOnlineBal;
    /** 扩展摘要 extendedRemark. */
    private String extendedRemark;
    /** 收付方帐号 ctpAcctNbr. */
    private String ctpAcctNbr;
    /** 收付方开户行行名 ctpBankName. */
    private String ctpBankName;
    /** 收付方开户行地址 ctpBankAddress. */
    private String ctpBankAddress;
    /** 母子公司帐号 fatOrSonAccount. */
    private String fatOrSonAccount;
    /** 母子公司名称 fatOrSonCompanyName. */
    private String fatOrSonCompanyName;
    /** 母子公司开户行行名 fatOrSonBankName. */
    private String fatOrSonBankName;
    /** 母子公司开户行地址 fatOrSonBankAddress. */
    private String fatOrSonBankAddress;
    /** 信息标志 infoFlag: 空 付方/子公司, 1 收方/子公司, 2 收方/母公司, 3 原收方/子公司. */
    private String infoFlag;
    /** 业务名称 businessName. */
    private String businessName;
    /** 网银业务摘要 businessText. */
    private String businessText;
    /** 网银流程实例号 requestNbr. */
    private String requestNbr;
    /** 网银业务参考号 yurRef. */
    private String yurRef;
    /** 虚拟户编号 virtualNbr. */
    private String virtualNbr;
    /** 商务支付订单号 mchOrderNbr. */
    private String mchOrderNbr;
    /** 记账卡号 transCardNbr. */
    private String transCardNbr;
    /** 保留字 reserve. */
    private String reserve;

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
    public String getStatementNo() { return statementNo; }
    public void setStatementNo(String statementNo) { this.statementNo = statementNo; }
    public LocalDateTime getTransactionTime() { return transactionTime; }
    public void setTransactionTime(LocalDateTime transactionTime) { this.transactionTime = transactionTime; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getCounterpartyName() { return counterpartyName; }
    public void setCounterpartyName(String counterpartyName) { this.counterpartyName = counterpartyName; }
    public String getCounterpartyAccountMasked() { return counterpartyAccountMasked; }
    public void setCounterpartyAccountMasked(String counterpartyAccountMasked) { this.counterpartyAccountMasked = counterpartyAccountMasked; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getValidationStatus() { return validationStatus; }
    public void setValidationStatus(String validationStatus) { this.validationStatus = validationStatus; }
    public String getValidationMessage() { return validationMessage; }
    public void setValidationMessage(String validationMessage) { this.validationMessage = validationMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getBankAccountNo() { return bankAccountNo; }
    public void setBankAccountNo(String bankAccountNo) { this.bankAccountNo = bankAccountNo; }
    public LocalDate getValueDate() { return valueDate; }
    public void setValueDate(LocalDate valueDate) { this.valueDate = valueDate; }
    public String getLoanCode() { return loanCode; }
    public void setLoanCode(String loanCode) { this.loanCode = loanCode; }
    public BigDecimal getSignedAmount() { return signedAmount; }
    public void setSignedAmount(BigDecimal signedAmount) { this.signedAmount = signedAmount; }
    public String getTextCode() { return textCode; }
    public void setTextCode(String textCode) { this.textCode = textCode; }
    public String getBillNumber() { return billNumber; }
    public void setBillNumber(String billNumber) { this.billNumber = billNumber; }
    public String getRemarkTextClt() { return remarkTextClt; }
    public void setRemarkTextClt(String remarkTextClt) { this.remarkTextClt = remarkTextClt; }
    public String getReversalFlag() { return reversalFlag; }
    public void setReversalFlag(String reversalFlag) { this.reversalFlag = reversalFlag; }
    public BigDecimal getAcctOnlineBal() { return acctOnlineBal; }
    public void setAcctOnlineBal(BigDecimal acctOnlineBal) { this.acctOnlineBal = acctOnlineBal; }
    public String getExtendedRemark() { return extendedRemark; }
    public void setExtendedRemark(String extendedRemark) { this.extendedRemark = extendedRemark; }
    public String getCtpAcctNbr() { return ctpAcctNbr; }
    public void setCtpAcctNbr(String ctpAcctNbr) { this.ctpAcctNbr = ctpAcctNbr; }
    public String getCtpBankName() { return ctpBankName; }
    public void setCtpBankName(String ctpBankName) { this.ctpBankName = ctpBankName; }
    public String getCtpBankAddress() { return ctpBankAddress; }
    public void setCtpBankAddress(String ctpBankAddress) { this.ctpBankAddress = ctpBankAddress; }
    public String getFatOrSonAccount() { return fatOrSonAccount; }
    public void setFatOrSonAccount(String fatOrSonAccount) { this.fatOrSonAccount = fatOrSonAccount; }
    public String getFatOrSonCompanyName() { return fatOrSonCompanyName; }
    public void setFatOrSonCompanyName(String fatOrSonCompanyName) { this.fatOrSonCompanyName = fatOrSonCompanyName; }
    public String getFatOrSonBankName() { return fatOrSonBankName; }
    public void setFatOrSonBankName(String fatOrSonBankName) { this.fatOrSonBankName = fatOrSonBankName; }
    public String getFatOrSonBankAddress() { return fatOrSonBankAddress; }
    public void setFatOrSonBankAddress(String fatOrSonBankAddress) { this.fatOrSonBankAddress = fatOrSonBankAddress; }
    public String getInfoFlag() { return infoFlag; }
    public void setInfoFlag(String infoFlag) { this.infoFlag = infoFlag; }
    public String getBusinessName() { return businessName; }
    public void setBusinessName(String businessName) { this.businessName = businessName; }
    public String getBusinessText() { return businessText; }
    public void setBusinessText(String businessText) { this.businessText = businessText; }
    public String getRequestNbr() { return requestNbr; }
    public void setRequestNbr(String requestNbr) { this.requestNbr = requestNbr; }
    public String getYurRef() { return yurRef; }
    public void setYurRef(String yurRef) { this.yurRef = yurRef; }
    public String getVirtualNbr() { return virtualNbr; }
    public void setVirtualNbr(String virtualNbr) { this.virtualNbr = virtualNbr; }
    public String getMchOrderNbr() { return mchOrderNbr; }
    public void setMchOrderNbr(String mchOrderNbr) { this.mchOrderNbr = mchOrderNbr; }
    public String getTransCardNbr() { return transCardNbr; }
    public void setTransCardNbr(String transCardNbr) { this.transCardNbr = transCardNbr; }
    public String getReserve() { return reserve; }
    public void setReserve(String reserve) { this.reserve = reserve; }
}
