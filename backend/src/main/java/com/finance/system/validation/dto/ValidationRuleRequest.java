package com.finance.system.validation.dto;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record ValidationRuleRequest(@NotBlank @Size(max=64) String ruleCode, @NotBlank @Size(max=128) String name,
                                    @NotBlank @Size(max=32) String ruleType, @NotBlank @Size(max=500) String expression,
                                    @Min(1) @Max(9999) Integer priority) {}
