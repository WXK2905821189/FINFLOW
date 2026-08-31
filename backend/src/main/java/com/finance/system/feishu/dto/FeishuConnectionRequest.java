package com.finance.system.feishu.dto;
import jakarta.validation.constraints.NotBlank;
public record FeishuConnectionRequest(@NotBlank String displayName, String tenantAlias) {}
