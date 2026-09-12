package com.saamp.trading.order;

import jakarta.annotation.PostConstruct;
import com.saamp.trading.config.TradingProperties;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Closes the persisted gate after Liquibase and before the production application is exposed. */
@Component
@DependsOn("liquibase")
public class ExecutionGateStartupGuard {
    private final ExecutionGateRepository gate;
    private final TradingProperties properties;
    private final Environment environment;

    public ExecutionGateStartupGuard(ExecutionGateRepository gate, TradingProperties properties, Environment environment) {
        this.gate = gate;
        this.properties = properties;
        this.environment = environment;
    }

    @PostConstruct
    void closeGate() {
        if (environment.acceptsProfiles(Profiles.of("prod")) || properties.getExecutionGate().isCloseOnStartup()) {
            gate.close("STARTUP_GUARD");
        }
    }
}
