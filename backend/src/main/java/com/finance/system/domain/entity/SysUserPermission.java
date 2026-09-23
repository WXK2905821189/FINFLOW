package com.finance.system.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** V45（W17 包 D）：账号级权限覆盖（GRANT / DENY），见 V45__sys_user_permission.sql。 */
@TableName("sys_user_permission")
public class SysUserPermission {

    public static final String EFFECT_GRANT = "GRANT";
    public static final String EFFECT_DENY = "DENY";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String permissionCode;
    private String effect;
    private Long createdBy;
    private LocalDateTime createdAt;

    public SysUserPermission() {
    }

    public SysUserPermission(Long userId, String permissionCode, String effect, Long createdBy) {
        this.userId = userId;
        this.permissionCode = permissionCode;
        this.effect = effect;
        this.createdBy = createdBy;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getPermissionCode() { return permissionCode; }
    public void setPermissionCode(String permissionCode) { this.permissionCode = permissionCode; }
    public String getEffect() { return effect; }
    public void setEffect(String effect) { this.effect = effect; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
