package com.finance.system.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("system_audit_event")
public class SystemAuditEvent {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    private Long companyId; private Long actorId; private String action; private String objectType; private String objectId;
    private String requestId; private String result; private String detail; private LocalDateTime createdAt;
    public Long getId(){return id;} public void setId(Long v){id=v;} public Long getCompanyId(){return companyId;} public void setCompanyId(Long v){companyId=v;}
    public Long getActorId(){return actorId;} public void setActorId(Long v){actorId=v;} public String getAction(){return action;} public void setAction(String v){action=v;}
    public String getObjectType(){return objectType;} public void setObjectType(String v){objectType=v;} public String getObjectId(){return objectId;} public void setObjectId(String v){objectId=v;}
    public String getRequestId(){return requestId;} public void setRequestId(String v){requestId=v;} public String getResult(){return result;} public void setResult(String v){result=v;}
    public String getDetail(){return detail;} public void setDetail(String v){detail=v;} public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
}
