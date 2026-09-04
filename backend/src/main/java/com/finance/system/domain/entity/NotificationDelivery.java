package com.finance.system.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("notification_delivery")
public class NotificationDelivery {
    @TableId(value="id", type= IdType.AUTO) private Long id; private Long companyId; private Long eventId; private Long destinationId;
    private String status; private Integer attemptCount; private String providerMessageId; private String lastError; private LocalDateTime sentAt; private LocalDateTime updatedAt;
    public Long getId(){return id;} public void setId(Long v){id=v;} public Long getCompanyId(){return companyId;} public void setCompanyId(Long v){companyId=v;}
    public Long getEventId(){return eventId;} public void setEventId(Long v){eventId=v;} public Long getDestinationId(){return destinationId;} public void setDestinationId(Long v){destinationId=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;} public Integer getAttemptCount(){return attemptCount;} public void setAttemptCount(Integer v){attemptCount=v;}
    public String getProviderMessageId(){return providerMessageId;} public void setProviderMessageId(String v){providerMessageId=v;} public String getLastError(){return lastError;} public void setLastError(String v){lastError=v;}
    public LocalDateTime getSentAt(){return sentAt;} public void setSentAt(LocalDateTime v){sentAt=v;} public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
