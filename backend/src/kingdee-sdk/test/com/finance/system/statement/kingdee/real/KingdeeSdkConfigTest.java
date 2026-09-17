package com.finance.system.statement.kingdee.real;

import com.finance.system.statement.kingdee.KingdeeVoucherGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REAL-mode Spring assembly regression (2026-09-17 ECS incident): the gateway bean was
 * conditional but its {@link KingdeeSdkClient} dependency had no bean definition, so the
 * first REAL-mode boot crashed with UnsatisfiedDependencyException and production was
 * rolled back to MOCK. Real mode must assemble exactly one client + one gateway; mock
 * mode must assemble neither (no credential requirement, mock gateway wins).
 */
class KingdeeSdkConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KingdeeSdkConfig.class, RealKingdeeVoucherGateway.class);

    @Test
    void realModeAssemblesClientAndGateway() {
        runner.withPropertyValues(
                        "kingdee.mock-mode=false",
                        "kingdee.real-enabled=true",
                        "kingdee.server-url=https://example.invalid/k3cloud/",
                        "kingdee.acct-id=test-acct",
                        "kingdee.app-id=test-app",
                        "kingdee.app-sec=test-secret",
                        "kingdee.user-name=tester")
                .run(context -> {
                    assertThat(context).hasSingleBean(KingdeeSdkClient.class);
                    assertThat(context).hasSingleBean(KingdeeVoucherGateway.class);
                });
    }

    @Test
    void mockModeAssemblesNeither() {
        runner.withPropertyValues("kingdee.mock-mode=true", "kingdee.real-enabled=true")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(KingdeeSdkClient.class);
                    assertThat(context).doesNotHaveBean(KingdeeVoucherGateway.class);
                });
    }
}
