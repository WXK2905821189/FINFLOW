package com.finance.system.validation.dto;
import java.time.LocalDateTime;
public record ValidationRuleResponse(Long id, String ruleCode, String name, String ruleType, String expression,
                                     Integer versionNo, String status, Integer priority, Long createdBy, LocalDateTime updatedAt) {}
