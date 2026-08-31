package com.finance.system.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("notification_event")
public class NotificationEvent {
    @TableId(value="id", type= IdType.AUTO) private Long id; private Long companyId; private String eventId; private String eventType;
    private String referenceNo; private String requestId; private String severity; private String summary; private LocalDateTime createdAt;
    public Long getId(){return id;} public void setId(Long v){id=v;} public Long getCompanyId(){return companyId;} public void setCompanyId(Long v){companyId=v;}
    public String getEventId(){return eventId;} public void setEventId(String v){eventId=v;} public String getEventType(){return eventType;} public void setEventType(String v){eventType=v;}
    public String getReferenceNo(){return referenceNo;} public void setReferenceNo(String v){referenceNo=v;} public String getRequestId(){return requestId;} public void setRequestId(String v){requestId=v;}
    public String getSeverity(){return severity;} public void setSeverity(String v){severity=v;} public String getSummary(){return summary;} public void setSummary(String v){summary=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
}
