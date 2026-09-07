package com.finance.system.statement.kingdee;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Activates the fail-closed placeholder: {@code kingdee.mock-mode=false} AND the real SDK
 * gateway is NOT enabled ({@code kingdee.real-enabled != true}). Preserves the pre-existing
 * behaviour where disabling the mock without a real adapter yields the UNAVAILABLE gateway.
 */
public class KingdeeUnavailableCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String mockMode = context.getEnvironment().getProperty("kingdee.mock-mode", "true");
        String realEnabled = context.getEnvironment().getProperty("kingdee.real-enabled", "false");
        return !"true".equalsIgnoreCase(mockMode) && !"true".equalsIgnoreCase(realEnabled);
    }
}
