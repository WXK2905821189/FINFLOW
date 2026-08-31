package com.finance.system.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("notification_policy")
public class NotificationPolicy {
    @TableId(value="id", type= IdType.AUTO) private Long id; private Long companyId; private String eventType; private Long destinationId;
    private Boolean enabled; private String templateVersion; private LocalDateTime createdAt; private LocalDateTime updatedAt;
    public Long getId(){return id;} public void setId(Long v){id=v;} public Long getCompanyId(){return companyId;} public void setCompanyId(Long v){companyId=v;}
    public String getEventType(){return eventType;} public void setEventType(String v){eventType=v;} public Long getDestinationId(){return destinationId;} public void setDestinationId(Long v){destinationId=v;}
    public Boolean getEnabled(){return enabled;} public void setEnabled(Boolean v){enabled=v;} public String getTemplateVersion(){return templateVersion;} public void setTemplateVersion(String v){templateVersion=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;} public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
