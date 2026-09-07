package com.finance.system.statement.kingdee;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Activates the real SDK gateway: {@code kingdee.mock-mode != true} AND
 * {@code kingdee.real-enabled=true}. The bean class itself lives in the
 * {@code kingdee-sdk} Maven profile source folder (official vendor SDK), so this
 * condition only ever matches on builds where that profile is active.
 */
public class KingdeeRealModeCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String mockMode = context.getEnvironment().getProperty("kingdee.mock-mode", "true");
        String realEnabled = context.getEnvironment().getProperty("kingdee.real-enabled", "false");
        return !"true".equalsIgnoreCase(mockMode) && "true".equalsIgnoreCase(realEnabled);
    }
}
