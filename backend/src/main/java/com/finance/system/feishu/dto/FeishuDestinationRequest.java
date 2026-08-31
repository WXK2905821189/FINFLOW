package com.finance.system.feishu.dto;
import jakarta.validation.constraints.NotBlank;
public record FeishuDestinationRequest(Long connectionId, @NotBlank String destinationType, @NotBlank String destinationKey, @NotBlank String displayName) {}
