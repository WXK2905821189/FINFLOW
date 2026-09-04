package com.finance.system.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("bank_data_sync_task")
public class BankDataSyncTask {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long companyId;
    private String taskNo;
    private String adapterCode;
    private String mappingVersion;
    private Long connectionId;
    private Long bankAccountId;
    private Long requestedBy;
    private String requestId;
    private String syncKey;
    private String triggerType;
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    private String bankRequestNo;
    private String status;
    private Integer rawCount;
    private Integer normalizedCount;
    private Integer duplicateCount;
    private Integer invalidCount;
    private String errorMessage;
    /** 银行 Z1 口径借方合计（各页求和，带符号）；银行不报或未走到该页时为 null。 */
    private BigDecimal debitAmount;
    /** 银行 Z1 口径借方笔数合计。 */
    private Integer debitNums;
    /** 银行 Z1 口径贷方合计（各页求和，带符号）。 */
    private BigDecimal creditAmount;
    /** 银行 Z1 口径贷方笔数合计。 */
    private Integer creditNums;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCompanyId() { return companyId; }
    public void setCompanyId(Long companyId) { this.companyId = companyId; }
    public String getTaskNo() { return taskNo; }
    public void setTaskNo(String taskNo) { this.taskNo = taskNo; }
    public String getAdapterCode() { return adapterCode; }
    public void setAdapterCode(String adapterCode) { this.adapterCode = adapterCode; }
    public String getMappingVersion() { return mappingVersion; }
    public void setMappingVersion(String mappingVersion) { this.mappingVersion = mappingVersion; }
    public Long getConnectionId() { return connectionId; }
    public void setConnectionId(Long connectionId) { this.connectionId = connectionId; }
    public Long getBankAccountId() { return bankAccountId; }
    public void setBankAccountId(Long bankAccountId) { this.bankAccountId = bankAccountId; }
    public Long getRequestedBy() { return requestedBy; }
    public void setRequestedBy(Long requestedBy) { this.requestedBy = requestedBy; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getSyncKey() { return syncKey; }
    public void setSyncKey(String syncKey) { this.syncKey = syncKey; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public LocalDateTime getWindowStart() { return windowStart; }
    public void setWindowStart(LocalDateTime windowStart) { this.windowStart = windowStart; }
    public LocalDateTime getWindowEnd() { return windowEnd; }
    public void setWindowEnd(LocalDateTime windowEnd) { this.windowEnd = windowEnd; }
    public String getBankRequestNo() { return bankRequestNo; }
    public void setBankRequestNo(String bankRequestNo) { this.bankRequestNo = bankRequestNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getRawCount() { return rawCount; }
    public void setRawCount(Integer rawCount) { this.rawCount = rawCount; }
    public Integer getNormalizedCount() { return normalizedCount; }
    public void setNormalizedCount(Integer normalizedCount) { this.normalizedCount = normalizedCount; }
    public Integer getDuplicateCount() { return duplicateCount; }
    public void setDuplicateCount(Integer duplicateCount) { this.duplicateCount = duplicateCount; }
    public Integer getInvalidCount() { return invalidCount; }
    public void setInvalidCount(Integer invalidCount) { this.invalidCount = invalidCount; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public BigDecimal getDebitAmount() { return debitAmount; }
    public void setDebitAmount(BigDecimal debitAmount) { this.debitAmount = debitAmount; }
    public Integer getDebitNums() { return debitNums; }
    public void setDebitNums(Integer debitNums) { this.debitNums = debitNums; }
    public BigDecimal getCreditAmount() { return creditAmount; }
    public void setCreditAmount(BigDecimal creditAmount) { this.creditAmount = creditAmount; }
    public Integer getCreditNums() { return creditNums; }
    public void setCreditNums(Integer creditNums) { this.creditNums = creditNums; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
