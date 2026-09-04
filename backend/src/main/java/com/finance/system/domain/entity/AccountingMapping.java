package com.finance.system.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("accounting_mapping")
public class AccountingMapping {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    private Long companyId; private String mappingCode; private String name; private String direction;
    private String counterpartyKeyword; private String debitSubject; private String creditSubject; private String voucherTemplate;
    private Integer versionNo; private String status; private Long createdBy; private LocalDateTime createdAt; private LocalDateTime updatedAt;
    public Long getId(){return id;} public void setId(Long v){id=v;} public Long getCompanyId(){return companyId;} public void setCompanyId(Long v){companyId=v;}
    public String getMappingCode(){return mappingCode;} public void setMappingCode(String v){mappingCode=v;} public String getName(){return name;} public void setName(String v){name=v;}
    public String getDirection(){return direction;} public void setDirection(String v){direction=v;} public String getCounterpartyKeyword(){return counterpartyKeyword;} public void setCounterpartyKeyword(String v){counterpartyKeyword=v;}
    public String getDebitSubject(){return debitSubject;} public void setDebitSubject(String v){debitSubject=v;} public String getCreditSubject(){return creditSubject;} public void setCreditSubject(String v){creditSubject=v;}
    public String getVoucherTemplate(){return voucherTemplate;} public void setVoucherTemplate(String v){voucherTemplate=v;} public Integer getVersionNo(){return versionNo;} public void setVersionNo(Integer v){versionNo=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;} public Long getCreatedBy(){return createdBy;} public void setCreatedBy(Long v){createdBy=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;} public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
