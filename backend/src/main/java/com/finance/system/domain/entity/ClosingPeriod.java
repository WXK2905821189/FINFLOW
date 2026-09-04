package com.finance.system.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("closing_period")
public class ClosingPeriod {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    private Long companyId; private String period; private String status; private Integer totalCount; private Integer pendingCount;
    private Integer exceptionCount; private Integer unpostedCount; private Long confirmedBy; private LocalDateTime confirmedAt;
    private String requestId; private String note; private LocalDateTime createdAt; private LocalDateTime updatedAt;
    public Long getId(){return id;} public void setId(Long v){id=v;} public Long getCompanyId(){return companyId;} public void setCompanyId(Long v){companyId=v;}
    public String getPeriod(){return period;} public void setPeriod(String v){period=v;} public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public Integer getTotalCount(){return totalCount;} public void setTotalCount(Integer v){totalCount=v;} public Integer getPendingCount(){return pendingCount;} public void setPendingCount(Integer v){pendingCount=v;}
    public Integer getExceptionCount(){return exceptionCount;} public void setExceptionCount(Integer v){exceptionCount=v;} public Integer getUnpostedCount(){return unpostedCount;} public void setUnpostedCount(Integer v){unpostedCount=v;}
    public Long getConfirmedBy(){return confirmedBy;} public void setConfirmedBy(Long v){confirmedBy=v;} public LocalDateTime getConfirmedAt(){return confirmedAt;} public void setConfirmedAt(LocalDateTime v){confirmedAt=v;}
    public String getRequestId(){return requestId;} public void setRequestId(String v){requestId=v;} public String getNote(){return note;} public void setNote(String v){note=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;} public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
