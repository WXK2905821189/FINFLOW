package com.finance.system.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * AI 能力提示词覆盖（V38，W9 需求 4）。
 *
 * <p>三个 AI 能力（{@code accounting-suggestion} / {@code company-classification} /
 * {@code rule-import}）的系统提示词此前硬编码在 Service 常量里；本表存超管
 * （{@code ai:config}）在页面上保存的<strong>覆盖值</strong>。读取口径
 * （{@link com.finance.system.ai.AiPromptService#resolve}）：行存在且内容非空 →
 * 用覆盖值；行不存在（或被「重置」删除）→ 回落代码内默认。</p>
 *
 * <p>capability 与 {@code ai_call_log.action} / 能力开关同域；只允许目录内已登记的 key。
 * 提示词全局生效，是系统配置——与 ai_provider_config（供应商连接配置）分离。</p>
 */
@TableName("ai_prompt_override")
public class AiPromptOverride {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 能力名（目录内已登记的 key，如 accounting-suggestion）。 */
    private String capability;

    /** 覆盖后的系统提示词（≤20000 字符，服务端校验）。 */
    private String systemPrompt;

    /** 最后编辑人。 */
    private Long updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCapability() { return capability; }
    public void setCapability(String capability) { this.capability = capability; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
