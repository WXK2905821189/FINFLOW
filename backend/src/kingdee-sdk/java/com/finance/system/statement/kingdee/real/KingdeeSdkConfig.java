package com.finance.system.statement.kingdee.real;

import com.finance.system.statement.kingdee.KingdeeProperties;
import com.finance.system.statement.kingdee.KingdeeRealModeCondition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * Assembles {@link KingdeeSdkClient} only in real mode ({@code kingdee.mock-mode=false}
 * AND {@code kingdee.real-enabled=true}).
 *
 * <p>Incident record 2026-09-17: {@link RealKingdeeVoucherGateway} was a conditional
 * {@code @Component} injecting {@link KingdeeSdkClient}, but no bean definition for the
 * client existed anywhere — the first REAL-mode boot on ECS crashed with
 * {@code UnsatisfiedDependencyException: No qualifying bean of type 'KingdeeSdkClient'}
 * and was rolled back to MOCK. Latent since 2026-09-11 because the joint-test probes
 * called the Kingdee WebAPI directly (curl/PowerShell), bypassing Spring assembly.
 *
 * <p>Fail-fast on incomplete credentials is intentional: a REAL deployment missing any of
 * server-url/acct-id/app-id/app-sec/user-name must not start half-configured
 * ({@link KingdeeSdkClient} constructor throws, context aborts, health check stays down).
 */
@Configuration
@Conditional(KingdeeRealModeCondition.class)
@EnableConfigurationProperties(KingdeeProperties.class)
public class KingdeeSdkConfig {

    @Bean
    public KingdeeSdkClient kingdeeSdkClient(KingdeeProperties props) {
        return new KingdeeSdkClient(props);
    }
}
