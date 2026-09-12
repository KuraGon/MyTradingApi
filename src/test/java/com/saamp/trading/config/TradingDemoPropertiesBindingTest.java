package com.saamp.trading.config;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

class TradingDemoPropertiesBindingTest {
    @Test
    void environmentVariableBindsDemoEnabled() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("test-env",
                Map.of(
                        "TRADING_DEMO_ENABLED", "true")));

        TradingProperties.Demo demo = Binder.get(environment)
                .bind("trading.demo", TradingProperties.Demo.class)
                .orElseThrow(() -> new IllegalStateException("Configuration demo absente"));

        assertThat(demo.isEnabled()).isTrue();
    }

    @Test
    void applicationYamlConnectsTheExactDemoRuntimeVariable() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("test-env",
                Map.of("TRADING_DEMO_ENABLED", "true")));
        new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));

        assertThat(environment.getProperty("trading.demo.enabled")).isEqualTo("true");
        assertThat(Binder.get(environment).bind("trading.demo", TradingProperties.Demo.class)
                .orElseThrow(() -> new IllegalStateException("Configuration demo absente")).isEnabled()).isTrue();
    }
}
