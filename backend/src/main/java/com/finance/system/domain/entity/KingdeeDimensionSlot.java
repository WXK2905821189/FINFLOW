package com.finance.system.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 金蝶核算维度 → 弹性域槽位配置（V42，2026-09-21）。
 *
 * <p>槽位键（{@code FF100002} 这类）是**账套级**配置，官方文档不公开、只能报错驱动试出：
 * 银行账号 = ZDY0001 / FF100002 已实测（凭证 16043 保存成功，见
 * docs/kingdee-openapi/gl-voucher-calibration-20260921.md）；供应商/员工/业务线等待试。</p>
 *
 * <p>用户 2026-09-21 拍板：这些配置要**在系统界面直接改**，不靠改代码/发版——故落表不落常量。
 * 一条 dimension_type 一行；slot 为空表示尚未试出，推送时按「无槽位」跳过并在结果里标注，
 * 不猜槽位（猜错会静默记错账）。</p>
 */
@TableName("kingdee_dimension_slot")
public class KingdeeDimensionSlot {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 维度类型（引擎侧枚举）：BANK_ACCOUNT / SUPPLIER / CUSTOMER / EMPLOYEE / BUSINESS_LINE … */
    private String dimensionType;

    /** 金蝶侧维度名称（如「银行账号」），便于与账套「核算维度」界面逐字对账。 */
    private String dimensionName;

    /** 金蝶侧维度编码（如自定义维度 ZDY0001）；账套预置维度可能为空。 */
    private String dimensionCode;

    /** 弹性域槽位键（如 FF100002）；为空 = 尚未试出。 */
    private String slot;

    /** 维度种类：BASE_DATA（基础资料）/ ASSIST（辅助资料）/ CUSTOM（自定义维度）。 */
    private String dimensionKind;

    /** 停用后该维度不再注入。 */
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

    public String getDimensionName() {
        return dimensionName;
    }

    public void setDimensionName(String dimensionName) {
        this.dimensionName = dimensionName;
    }

    public String getDimensionCode() {
        return dimensionCode;
    }

    public void setDimensionCode(String dimensionCode) {
        this.dimensionCode = dimensionCode;
    }

    public String getSlot() {
        return slot;
    }

    public void setSlot(String slot) {
        this.slot = slot;
    }

    public String getDimensionKind() {
        return dimensionKind;
    }

    public void setDimensionKind(String dimensionKind) {
        this.dimensionKind = dimensionKind;
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
