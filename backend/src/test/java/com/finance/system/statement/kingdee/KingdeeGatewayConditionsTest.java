package com.finance.system.statement.kingdee;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Gateway routing matrix (mutually exclusive beans). Mirrors the documented contract:
 * mock-mode=true -> mock; mock-mode=false + real-enabled!=true -> unavailable;
 * mock-mode=false + real-enabled=true -> real (kingdee-sdk profile).
 */
class KingdeeGatewayConditionsTest {

    private final KingdeeRealModeCondition real = new KingdeeRealModeCondition();
    private final KingdeeUnavailableCondition unavailable = new KingdeeUnavailableCondition();
    private final AnnotatedTypeMetadata metadata = mock(AnnotatedTypeMetadata.class);

    private ConditionContext contextWith(MockEnvironment env) {
        ConditionContext context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(env);
        return context;
    }

    @Test
    void defaultIsMockMode() {
        ConditionContext context = contextWith(new MockEnvironment());
        assertFalse(real.matches(context, metadata));
        assertFalse(unavailable.matches(context, metadata));
    }

    @Test
    void mockDisabledWithoutRealEnabledIsUnavailable() {
        ConditionContext context = contextWith(new MockEnvironment()
                .withProperty("kingdee.mock-mode", "false"));
        assertFalse(real.matches(context, metadata));
        assertTrue(unavailable.matches(context, metadata));
    }

    @Test
    void mockDisabledWithRealEnabledIsRealMode() {
        ConditionContext context = contextWith(new MockEnvironment()
                .withProperty("kingdee.mock-mode", "false")
                .withProperty("kingdee.real-enabled", "true"));
        assertTrue(real.matches(context, metadata));
        assertFalse(unavailable.matches(context, metadata));
    }

    @Test
    void mockModeTrueAlwaysWins() {
        ConditionContext context = contextWith(new MockEnvironment()
                .withProperty("kingdee.mock-mode", "true")
                .withProperty("kingdee.real-enabled", "true"));
        assertFalse(real.matches(context, metadata));
        assertFalse(unavailable.matches(context, metadata));
    }
}
