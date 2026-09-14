package com.finance.system.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 飞书自建应用凭证配置（V29 单例行，id=1）。App Secret AES-256-GCM 加密落库，
 * 明文永不回显（读接口只给 hint 尾 4 位 + 是否已配置布尔位）。
 */
@TableName("feishu_app_config")
public class FeishuAppConfig {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String appId;
    private String appSecretCipher;
    private String appSecretHint;
    private LocalDateTime verifiedAt;
    private Long verifiedBy;
    private String verifiedTenant;
    private Long updatedBy;
    private LocalDateTime updatedAt;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getAppSecretCipher() { return appSecretCipher; }
    public void setAppSecretCipher(String appSecretCipher) { this.appSecretCipher = appSecretCipher; }
    public String getAppSecretHint() { return appSecretHint; }
    public void setAppSecretHint(String appSecretHint) { this.appSecretHint = appSecretHint; }
    public LocalDateTime getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(LocalDateTime verifiedAt) { this.verifiedAt = verifiedAt; }
    public Long getVerifiedBy() { return verifiedBy; }
    public void setVerifiedBy(Long verifiedBy) { this.verifiedBy = verifiedBy; }
    public String getVerifiedTenant() { return verifiedTenant; }
    public void setVerifiedTenant(String verifiedTenant) { this.verifiedTenant = verifiedTenant; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
