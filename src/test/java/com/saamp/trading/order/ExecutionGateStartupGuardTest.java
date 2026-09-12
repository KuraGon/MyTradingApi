package com.saamp.trading.order;

import org.junit.jupiter.api.Test;
import com.saamp.trading.config.TradingProperties;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionGateStartupGuardTest {
    @Test
    void prodClosesThePersistedGateWhenThePropertyIsAbsent() {
        ExecutionGateRepository gate = mock(ExecutionGateRepository.class);
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(Profiles.of("prod"))).thenReturn(true);
        TradingProperties properties = new TradingProperties();

        new ExecutionGateStartupGuard(gate, properties, environment).closeGate();

        verify(gate).close("STARTUP_GUARD");
    }

    @Test
    void prodAlwaysClosesThePersistedGateEvenWhenThePropertyIsFalse() {
        ExecutionGateRepository gate = mock(ExecutionGateRepository.class);
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(Profiles.of("prod"))).thenReturn(true);
        TradingProperties properties = new TradingProperties();
        properties.getExecutionGate().setCloseOnStartup(false);

        new ExecutionGateStartupGuard(gate, properties, environment).closeGate();

        verify(gate).close("STARTUP_GUARD");
    }

    @Test
    void nonProductionKeepsTheExistingOptInBehaviour() {
        ExecutionGateRepository gate = mock(ExecutionGateRepository.class);
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(Profiles.of("prod"))).thenReturn(false);
        TradingProperties properties = new TradingProperties();

        new ExecutionGateStartupGuard(gate, properties, environment).closeGate();

        verify(gate, never()).close("STARTUP_GUARD");
        properties.getExecutionGate().setCloseOnStartup(true);
        new ExecutionGateStartupGuard(gate, properties, environment).closeGate();
        verify(gate).close("STARTUP_GUARD");
    }
}
