package com.finance.system.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("feishu_destination")
public class FeishuDestination {
    @TableId(value="id", type= IdType.AUTO) private Long id; private Long companyId; private Long connectionId;
    private String destinationType; private String destinationKey; private String displayName; private Boolean enabled;
    private LocalDateTime createdAt; private LocalDateTime updatedAt;
    public Long getId(){return id;} public void setId(Long v){id=v;} public Long getCompanyId(){return companyId;} public void setCompanyId(Long v){companyId=v;}
    public Long getConnectionId(){return connectionId;} public void setConnectionId(Long v){connectionId=v;} public String getDestinationType(){return destinationType;} public void setDestinationType(String v){destinationType=v;}
    public String getDestinationKey(){return destinationKey;} public void setDestinationKey(String v){destinationKey=v;} public String getDisplayName(){return displayName;} public void setDisplayName(String v){displayName=v;}
    public Boolean getEnabled(){return enabled;} public void setEnabled(Boolean v){enabled=v;} public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
