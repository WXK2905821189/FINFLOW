package com.finance.system.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 金蝶核算维度「值映射」（V42，2026-09-21）：FINFLOW 侧来源值 → 金蝶档案编码。
 *
 * <p>为什么需要：金蝶核算维度要的是**档案编码**（供应商 {@code VEN00511}、客户编码、
 * 员工编码），而 FINFLOW 手里只有名称。规则引擎此前直接把对手方名称当维度值塞进 payload，
 * 语义不对（评估文档缺口 1）。本表承担这层翻译，可由财务在系统界面维护。</p>
 *
 * <p>{@code sourceKind} 决定比较方式：NAME=精确、KEYWORD=包含（业务线这类）、
 * ACCOUNT=账号精确、CODE=编码精确。{@code orgCode} 为空串表示通用；同一来源值在不同
 * 主体对应不同档案时，用组织限定行消歧（与 bank_account.kingdee_account_number 同思路）。</p>
 */
@TableName("kingdee_dimension_mapping")
public class KingdeeDimensionMapping {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 维度类型，与 kingdee_dimension_slot.dimension_type 对应。 */
    private String dimensionType;

    /** FINFLOW 侧来源值（对手方名称 / 员工姓名 / 业务线关键词 / 我方账户号）。 */
    private String sourceKey;

    /** 比较方式：NAME / KEYWORD / ACCOUNT / CODE。 */
    private String sourceKind;

    /** 金蝶侧档案编码（如 VEN00511）——最终注入凭证分录的值。 */
    private String kingdeeValue;

    /** 金蝶侧档案显示名（便于人工核对，可空）。 */
    private String kingdeeName;

    /** 限定金蝶组织编码；空串 = 通用。 */
    private String orgCode;

    /** 停用后不再参与解析。 */
    private Boolean enabled;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDimensionType() {
        return dimensionType;
    }

    public void setDimensionType(String dimensionType) {
        this.dimensionType = dimensionType;
    }

    public String getSourceKey() {
        return sourceKey;
    }

    public void setSourceKey(String sourceKey) {
        this.sourceKey = sourceKey;
    }

    public String getSourceKind() {
        return sourceKind;
    }

    public void setSourceKind(String sourceKind) {
        this.sourceKind = sourceKind;
    }

    public String getKingdeeValue() {
        return kingdeeValue;
    }

    public void setKingdeeValue(String kingdeeValue) {
        this.kingdeeValue = kingdeeValue;
    }

    public String getKingdeeName() {
        return kingdeeName;
    }

    public void setKingdeeName(String kingdeeName) {
        this.kingdeeName = kingdeeName;
    }

    public String getOrgCode() {
        return orgCode;
    }

    public void setOrgCode(String orgCode) {
        this.orgCode = orgCode;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
