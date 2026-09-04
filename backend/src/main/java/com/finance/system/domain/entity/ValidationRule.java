package com.finance.system.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("validation_rule")
public class ValidationRule {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    private Long companyId; private String ruleCode; private String name; private String ruleType;
    private String expression; private Integer versionNo; private String status; private Integer priority;
    private Long createdBy; private LocalDateTime createdAt; private LocalDateTime updatedAt;
    public Long getId(){return id;} public void setId(Long v){id=v;} public Long getCompanyId(){return companyId;} public void setCompanyId(Long v){companyId=v;}
    public String getRuleCode(){return ruleCode;} public void setRuleCode(String v){ruleCode=v;} public String getName(){return name;} public void setName(String v){name=v;}
    public String getRuleType(){return ruleType;} public void setRuleType(String v){ruleType=v;} public String getExpression(){return expression;} public void setExpression(String v){expression=v;}
    public Integer getVersionNo(){return versionNo;} public void setVersionNo(Integer v){versionNo=v;} public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public Integer getPriority(){return priority;} public void setPriority(Integer v){priority=v;} public Long getCreatedBy(){return createdBy;} public void setCreatedBy(Long v){createdBy=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;} public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
