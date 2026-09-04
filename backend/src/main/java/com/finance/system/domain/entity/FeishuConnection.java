package com.finance.system.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("feishu_connection")
public class FeishuConnection {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    private Long companyId; private String connectionCode; private String displayName; private String tenantAlias;
    private String mode; private String status; private LocalDateTime createdAt; private LocalDateTime updatedAt;
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public Long getCompanyId(){return companyId;} public void setCompanyId(Long v){companyId=v;}
    public String getConnectionCode(){return connectionCode;} public void setConnectionCode(String v){connectionCode=v;}
    public String getDisplayName(){return displayName;} public void setDisplayName(String v){displayName=v;}
    public String getTenantAlias(){return tenantAlias;} public void setTenantAlias(String v){tenantAlias=v;}
    public String getMode(){return mode;} public void setMode(String v){mode=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
