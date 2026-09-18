package com.finance.system.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 金蝶凭证规则引擎 — 规则模板行（V34，设计源 docs/kingdee-openapi/voucher-rule-engine-20260916.md）。
 *
 * <p>银行流水 → GL_VOUCHER 规则引擎的规则数据。种子 22 条来自财务侧《规则映射表》
 * （docs/kingdee-openapi/voucher-rules-seed-20260916.json），后续规则经新迁移追加。</p>
 *
 * <p>JSON 列（match/debit/credit/extra）的承载结构见
 * {@code com.finance.system.statement.voucherrule.dto}；match_json 中 amountGate/note
 * 等注释键为文档性质，匹配器只消费 logic/conditions。</p>
 */
@TableName("kingdee_voucher_rule")
public class KingdeeVoucherRule {

    /** 技术主键（业务身份是 rule_no，来自财务映射表的规则号 1..22）。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 财务映射表规则号（稳定业务标识，唯一）。 */
    private Integer ruleNo;

    /** 业务类型（如「即时行乐报销」「集团主体往来」），页面分组展示用。 */
    private String businessType;

    /** 类别（费用类/往来类/五险一金/薪资/收入类…）。 */
    private String category;

    /** 优先级：数值越小越先匹配（对手方精确 20 &lt; 附言宽泛兜底 30）。 */
    private Integer priority;

    /** 适用主体范围：FINFLOW 主体编码逗号分隔（300/400/710/720/900），ALL=全部主体。 */
    private String scopeOrgs;

    /** 适用银行渠道：CITIC/CMB 逗号分隔，空串=全部渠道。 */
    private String scopeBankChannels;

    /** 资金方向预过滤：EXPENSE / INCOME / ANY。 */
    private String direction;

    /** 金额下界（预留：社保 vs 个税同附言区分，阈值待财务提供）。 */
    private BigDecimal amountMin;

    /** 金额上界（预留，同上）。 */
    private BigDecimal amountMax;

    /** 匹配条件 JSON：{logic, conditions:[{field, op, values}]}（+注释键）。 */
    private String matchJson;

    /** 借方分录模板 JSON 数组：[{account, name, dimension, value?, branches?, share}]。 */
    private String debitLinesJson;

    /** 贷方分录模板 JSON 数组（结构同借方）。 */
    private String creditLinesJson;

    /** 「直接确认费用」第二张凭证模板 JSON（{note?, debitLines, creditLines}），仅规则 1、14。 */
    private String extraVoucherJson;

    /** 启用开关：停用的规则被匹配器跳过。 */
    private Boolean enabled;

    /** 财务原文备注（映射表原文/口径说明）。 */
    private String remark;

    /** 规则分组（W4 规则中心）：kingdee_rule_group.id，seed 规则挂在「财务默认规则」。 */
    private Long groupId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getRuleNo() {
        return ruleNo;
    }

    public void setRuleNo(Integer ruleNo) {
        this.ruleNo = ruleNo;
    }

    public String getBusinessType() {
        return businessType;
    }

    public void setBusinessType(String businessType) {
        this.businessType = businessType;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public String getScopeOrgs() {
        return scopeOrgs;
    }

    public void setScopeOrgs(String scopeOrgs) {
        this.scopeOrgs = scopeOrgs;
    }

    public String getScopeBankChannels() {
        return scopeBankChannels;
    }

    public void setScopeBankChannels(String scopeBankChannels) {
        this.scopeBankChannels = scopeBankChannels;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public BigDecimal getAmountMin() {
        return amountMin;
    }

    public void setAmountMin(BigDecimal amountMin) {
        this.amountMin = amountMin;
    }

    public BigDecimal getAmountMax() {
        return amountMax;
    }

    public void setAmountMax(BigDecimal amountMax) {
        this.amountMax = amountMax;
    }

    public String getMatchJson() {
        return matchJson;
    }

    public void setMatchJson(String matchJson) {
        this.matchJson = matchJson;
    }

    public String getDebitLinesJson() {
        return debitLinesJson;
    }

    public void setDebitLinesJson(String debitLinesJson) {
        this.debitLinesJson = debitLinesJson;
    }

    public String getCreditLinesJson() {
        return creditLinesJson;
    }

    public void setCreditLinesJson(String creditLinesJson) {
        this.creditLinesJson = creditLinesJson;
    }

    public String getExtraVoucherJson() {
        return extraVoucherJson;
    }

    public void setExtraVoucherJson(String extraVoucherJson) {
        this.extraVoucherJson = extraVoucherJson;
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
