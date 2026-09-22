package com.finance.system.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("statement_record")
public class StatementRecord {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long companyId;
    private Long batchId;
    private String statementNo;
    private Long bankAccountId;
    private LocalDateTime transactionTime;
    private String direction;
    private BigDecimal amount;
    private String currency;
    private String counterpartyName;
    private String counterpartyAccount;
    private String summary;
    private String rawPayload;
    private String validationStatus;
    private String validationMessage;
    private String reviewStatus;
    private String reviewComment;
    /** V33：结构化 AI 凭证建议（分录数组+逐行置信度+人工修正标记），见 VoucherSuggestionDto。 */
    private String aiSuggestionJson;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private String pushStatus;
    private String voucherNo;
    private String pushMessage;
    private LocalDateTime pushedAt;
    /** V39（W10）：凭证撤回 —— 撤回时间/操作人（review_status 置 WITHDRAWN 时写入）。 */
    private LocalDateTime withdrawnAt;
    private Long withdrawnBy;
    /** V44（W16-A2）：问题凭证落桶标记 —— 非空 = 在问题凭证桶；推送成功（GL_PUSHED）时清空出列。 */
    private String problemType;
    private String problemReason;
    /** A2 编辑器的人工编辑态（分录数组 JSON，VoucherProblemEditDoc 序列化）。 */
    private String problemEditJson;
    private Long problemUpdatedBy;
    private LocalDateTime problemUpdatedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCompanyId() { return companyId; }
    public void setCompanyId(Long companyId) { this.companyId = companyId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getStatementNo() { return statementNo; }
    public void setStatementNo(String statementNo) { this.statementNo = statementNo; }
    public Long getBankAccountId() { return bankAccountId; }
    public void setBankAccountId(Long bankAccountId) { this.bankAccountId = bankAccountId; }
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
    public String getCounterpartyAccount() { return counterpartyAccount; }
    public void setCounterpartyAccount(String counterpartyAccount) { this.counterpartyAccount = counterpartyAccount; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getRawPayload() { return rawPayload; }
    public void setRawPayload(String rawPayload) { this.rawPayload = rawPayload; }
    public String getValidationStatus() { return validationStatus; }
    public void setValidationStatus(String validationStatus) { this.validationStatus = validationStatus; }
    public String getValidationMessage() { return validationMessage; }
    public void setValidationMessage(String validationMessage) { this.validationMessage = validationMessage; }
    public String getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(String reviewStatus) { this.reviewStatus = reviewStatus; }
    public String getReviewComment() { return reviewComment; }
    public void setReviewComment(String reviewComment) { this.reviewComment = reviewComment; }
    public String getAiSuggestionJson() { return aiSuggestionJson; }
    public void setAiSuggestionJson(String aiSuggestionJson) { this.aiSuggestionJson = aiSuggestionJson; }
    public Long getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(Long reviewedBy) { this.reviewedBy = reviewedBy; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }
    public String getPushStatus() { return pushStatus; }
    public void setPushStatus(String pushStatus) { this.pushStatus = pushStatus; }
    public String getVoucherNo() { return voucherNo; }
    public void setVoucherNo(String voucherNo) { this.voucherNo = voucherNo; }
    public String getPushMessage() { return pushMessage; }
    public void setPushMessage(String pushMessage) { this.pushMessage = pushMessage; }
    public LocalDateTime getPushedAt() { return pushedAt; }
    public void setPushedAt(LocalDateTime pushedAt) { this.pushedAt = pushedAt; }
    public LocalDateTime getWithdrawnAt() { return withdrawnAt; }
    public void setWithdrawnAt(LocalDateTime withdrawnAt) { this.withdrawnAt = withdrawnAt; }
    public Long getWithdrawnBy() { return withdrawnBy; }
    public void setWithdrawnBy(Long withdrawnBy) { this.withdrawnBy = withdrawnBy; }
    public String getProblemType() { return problemType; }
    public void setProblemType(String problemType) { this.problemType = problemType; }
    public String getProblemReason() { return problemReason; }
    public void setProblemReason(String problemReason) { this.problemReason = problemReason; }
    public String getProblemEditJson() { return problemEditJson; }
    public void setProblemEditJson(String problemEditJson) { this.problemEditJson = problemEditJson; }
    public Long getProblemUpdatedBy() { return problemUpdatedBy; }
    public void setProblemUpdatedBy(Long problemUpdatedBy) { this.problemUpdatedBy = problemUpdatedBy; }
    public LocalDateTime getProblemUpdatedAt() { return problemUpdatedAt; }
    public void setProblemUpdatedAt(LocalDateTime problemUpdatedAt) { this.problemUpdatedAt = problemUpdatedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
