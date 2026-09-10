package com.saamp.trading.risk.monitor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class RiskMonitorConfigurationTest {
    @Test void configuredThresholdsAreBound() {
        new ApplicationContextRunner().withUserConfiguration(Binding.class).withPropertyValues(
                "trading.risk-monitor.interval=1s", "trading.risk-monitor.max-price-age=60s",
                "trading.risk-monitor.hysteresis-points=0.2", "trading.risk-monitor.rearm-duration=10s",
                "trading.risk-monitor.max-observation-gap=5s", "trading.risk-monitor.batch-size=10",
                "trading.risk-monitor.claim-lease=30s", "trading.risk-monitor.retry-initial=1s",
                "trading.risk-monitor.retry-max=4s", "trading.risk-monitor.smtp.host=localhost",
                "trading.risk-monitor.smtp.port=2525", "trading.risk-monitor.smtp.from=monitor@example.invalid",
                "trading.risk-monitor.smtp.recipients=recipient@example.invalid", "trading.risk-monitor.smtp.timeout=1s",
                "trading.risk-monitor.warning-threshold=110", "trading.risk-monitor.critical-threshold=108",
                "trading.risk-monitor.liquidation-required-threshold=106"
        ).run(context->{
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RiskMonitorProperties.class)).hasFieldOrPropertyWithValue("warningThreshold",new java.math.BigDecimal("110"))
                    .hasFieldOrPropertyWithValue("criticalThreshold",new java.math.BigDecimal("108"))
                    .hasFieldOrPropertyWithValue("liquidationRequiredThreshold",new java.math.BigDecimal("106"));
        });
    }
    @Test void defaultDisabledNeedsNeitherDatabaseNorSmtpConfiguration() {
        new ApplicationContextRunner().withUserConfiguration(RiskMonitorConfiguration.class).run(context->{
            assertThat(context).hasNotFailed().doesNotHaveBean(RiskMonitorService.class)
                    .doesNotHaveBean(RiskMonitorMailWorker.class).doesNotHaveBean(RiskMonitorProperties.class);
        });
    }
    @Test void explicitDisabledDoesNotStartWorkers() {
        new ApplicationContextRunner().withUserConfiguration(RiskMonitorConfiguration.class)
                .withPropertyValues("trading.risk-monitor.enabled=false").run(context->{
                    assertThat(context).hasNotFailed().doesNotHaveBean(RiskMonitorRepository.class);
                });
    }
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties(RiskMonitorProperties.class)
    static class Binding { }

    @Test void externalPropertiesBindWithoutBusinessDefaults() {
        new ApplicationContextRunner().withUserConfiguration(Binding.class).withPropertyValues(
                "trading.risk-monitor.interval=1s", "trading.risk-monitor.max-price-age=60s",
                "trading.risk-monitor.hysteresis-points=0.2", "trading.risk-monitor.rearm-duration=10s",
                "trading.risk-monitor.max-observation-gap=5s", "trading.risk-monitor.batch-size=10",
                "trading.risk-monitor.claim-lease=30s", "trading.risk-monitor.retry-initial=1s",
                "trading.risk-monitor.retry-max=4s", "trading.risk-monitor.smtp.host=localhost",
                "trading.risk-monitor.smtp.port=2525", "trading.risk-monitor.smtp.from=monitor@example.invalid",
                "trading.risk-monitor.smtp.recipients=recipient@example.invalid", "trading.risk-monitor.smtp.timeout=1s"
        ).run(context->{
            assertThat(context).hasNotFailed();
            var properties=context.getBean(RiskMonitorProperties.class);
            assertThat(properties.interval()).isEqualTo(java.time.Duration.ofSeconds(1));
            assertThat(properties.hysteresisPoints()).isEqualByComparingTo("0.2");
            assertThat(properties.smtp().recipients()).containsExactly("recipient@example.invalid");
            assertThat(properties.warningThreshold()).isEqualByComparingTo("105");
            assertThat(properties.criticalThreshold()).isEqualByComparingTo("104");
            assertThat(properties.liquidationRequiredThreshold()).isEqualByComparingTo("102");
            assertThat(properties.maxAttempts()).isEqualTo(5);
        });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"warning-threshold=104","critical-threshold=102","liquidation-required-threshold=106","max-attempts=0","max-attempts=-1"})
    void invalidThresholdOrderAndAttemptLimitFailBinding(String invalid) {
        new ApplicationContextRunner().withUserConfiguration(Binding.class).withPropertyValues(
                "trading.risk-monitor.interval=1s", "trading.risk-monitor.max-price-age=60s",
                "trading.risk-monitor.hysteresis-points=0.2", "trading.risk-monitor.rearm-duration=10s",
                "trading.risk-monitor.max-observation-gap=5s", "trading.risk-monitor.batch-size=10",
                "trading.risk-monitor.claim-lease=30s", "trading.risk-monitor.retry-initial=1s",
                "trading.risk-monitor.retry-max=4s", "trading.risk-monitor.smtp.host=localhost",
                "trading.risk-monitor.smtp.port=2525", "trading.risk-monitor.smtp.from=monitor@example.invalid",
                "trading.risk-monitor.smtp.recipients=recipient@example.invalid", "trading.risk-monitor.smtp.timeout=1s",
                "trading.risk-monitor."+invalid).run(context->assertThat(context).hasFailed());
    }

}
