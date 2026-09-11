package com.saamp.trading.account;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

class EffectiveBalancePropertiesTest {
    private ApplicationContextRunner environment(Map<String,Object> variables) {
        return new ApplicationContextRunner().withUserConfiguration(Binding.class)
            .withInitializer(context->{
                var sources=context.getEnvironment().getPropertySources();
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                sources.replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,variables));
            });
    }

    @ParameterizedTest
    @ValueSource(strings={"TRADING_EFFECTIVE_BALANCE_OVERLAY_CUTOVER_AT","TRADING_EFFECTIVEBALANCE_OVERLAYCUTOVERAT"})
    void bindsRealEnvironmentPropertySource(String variable) {
        environment(Map.of(variable,"2026-09-11T15:00:00Z"))
            .withPropertyValues("trading.effective-balance.mode=ENFORCED")
            .run(context->{
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(EffectiveBalanceProperties.class).getOverlayCutoverAt())
                    .isEqualTo(Instant.parse("2026-09-11T15:00:00Z"));
            });
    }

    @Test void legacyDefaultStartsWithoutCutover() {
        environment(Map.of()).run(context->{
            assertThat(context).hasNotFailed();
            var properties=context.getBean(EffectiveBalanceProperties.class);
            assertThat(properties.getMode()).isEqualTo(EffectiveBalanceProperties.Mode.LEGACY);
            assertThat(properties.getOverlayCutoverAt()).isNull();
        });
    }

    @Test void explicitLegacyStartsWithoutCutover() {
        environment(Map.of("TRADING_EFFECTIVEBALANCE_MODE","LEGACY")).run(context->{
            assertThat(context).hasNotFailed();
            var properties=context.getBean(EffectiveBalanceProperties.class);
            assertThat(properties.getMode()).isEqualTo(EffectiveBalanceProperties.Mode.LEGACY);
            assertThat(properties.getOverlayCutoverAt()).isNull();
        });
    }

    @ParameterizedTest @ValueSource(strings={"SHADOW","ENFORCED"})
    void officialModeFromEnvironmentRequiresCutover(String mode) {
        environment(Map.of("TRADING_EFFECTIVEBALANCE_MODE",mode))
            .run(context->assertThat(context).hasFailed().getFailure()
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("trading.effective-balance.overlay-cutover-at is required in SHADOW/ENFORCED"));
    }

    @EnableConfigurationProperties(EffectiveBalanceProperties.class)
    static class Binding { }
}
