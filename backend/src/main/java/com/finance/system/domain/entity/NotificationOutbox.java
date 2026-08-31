package com.finance.system.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("notification_outbox")
public class NotificationOutbox {
    @TableId(value="id", type= IdType.AUTO) private Long id; private Long companyId; private Long eventId; private String status;
    private Integer attempts; private String lastError; private LocalDateTime availableAt; private LocalDateTime createdAt;
    public Long getId(){return id;} public void setId(Long v){id=v;} public Long getCompanyId(){return companyId;} public void setCompanyId(Long v){companyId=v;}
    public Long getEventId(){return eventId;} public void setEventId(Long v){eventId=v;} public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public Integer getAttempts(){return attempts;} public void setAttempts(Integer v){attempts=v;} public String getLastError(){return lastError;} public void setLastError(String v){lastError=v;}
    public LocalDateTime getAvailableAt(){return availableAt;} public void setAvailableAt(LocalDateTime v){availableAt=v;} public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
}
