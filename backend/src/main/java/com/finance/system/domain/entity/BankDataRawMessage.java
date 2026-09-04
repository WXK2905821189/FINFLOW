package com.finance.system.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("bank_data_raw_message")
public class BankDataRawMessage {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long companyId;
    private Long taskId;
    private String adapterCode;
    private String mappingVersion;
    private String bankRequestNo;
    private String contentSha256;
    private String payload;
    private LocalDateTime purgedAt;
    private LocalDateTime receivedAt;
    private LocalDateTime retentionUntil;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCompanyId() { return companyId; }
    public void setCompanyId(Long companyId) { this.companyId = companyId; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getAdapterCode() { return adapterCode; }
    public void setAdapterCode(String adapterCode) { this.adapterCode = adapterCode; }
    public String getMappingVersion() { return mappingVersion; }
    public void setMappingVersion(String mappingVersion) { this.mappingVersion = mappingVersion; }
    public String getBankRequestNo() { return bankRequestNo; }
    public void setBankRequestNo(String bankRequestNo) { this.bankRequestNo = bankRequestNo; }
    public String getContentSha256() { return contentSha256; }
    public void setContentSha256(String contentSha256) { this.contentSha256 = contentSha256; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public LocalDateTime getPurgedAt() { return purgedAt; }
    public void setPurgedAt(LocalDateTime purgedAt) { this.purgedAt = purgedAt; }
    public LocalDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(LocalDateTime receivedAt) { this.receivedAt = receivedAt; }
    public LocalDateTime getRetentionUntil() { return retentionUntil; }
    public void setRetentionUntil(LocalDateTime retentionUntil) { this.retentionUntil = retentionUntil; }
}
