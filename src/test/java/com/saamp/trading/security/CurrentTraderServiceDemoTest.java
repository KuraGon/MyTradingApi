package com.saamp.trading.security;

import com.saamp.trading.domain.TradingMode;
import com.saamp.trading.common.TradingException;
import com.saamp.trading.config.TradingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentTraderServiceDemoTest {
    @Test
    void demoJwtIsAcceptedOnlyWhenRuntimeDemoIsEnabled() {
        TradingProperties properties = new TradingProperties();
        TradingDemoGuard guard = new TradingDemoGuard(properties);
        CurrentTraderService service = new CurrentTraderService(guard);
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt("DEMO"));

        assertThatThrownBy(() -> service.current(authentication))
                .isInstanceOfSatisfying(TradingException.class,
                        error -> assertThat(error.getCode()).isEqualTo("DEMO_DISABLED"));

        properties.getDemo().setEnabled(true);
        assertThat(service.current(authentication).tradingMode()).isEqualTo(TradingMode.DEMO);
    }

    @Test
    void enabledDemoJwtIsAllowedWithTheSimulatedProvider() {
        TradingProperties properties = new TradingProperties();
        properties.getDemo().setEnabled(true);
        properties.getProvider().setMode("SIMULATED");
        TradingDemoGuard guard = new TradingDemoGuard(properties);
        CurrentTraderService service = new CurrentTraderService(guard);

        TradingMode mode = service.current(new JwtAuthenticationToken(jwt("DEMO"))).tradingMode();

        assertThatCode(() -> guard.assertSubmissionAllowed(mode)).doesNotThrowAnyException();
    }

    @Test
    void demoJwtIsAcceptedInProductionWhenExplicitlyEnabled() {
        TradingProperties properties = new TradingProperties();
        properties.getDemo().setEnabled(true);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        CurrentTraderService service = new CurrentTraderService(new TradingDemoGuard(properties, environment));

        assertThat(service.current(new JwtAuthenticationToken(jwt("DEMO"))).tradingMode()).isEqualTo(TradingMode.DEMO);
    }

    @Test
    void liveJwtWithoutTradingModeRemainsCompatible() {
        TradingProperties properties = new TradingProperties();
        CurrentTraderService service = new CurrentTraderService(new TradingDemoGuard(properties));

        assertThat(service.current(new JwtAuthenticationToken(jwt(null))).tradingMode()).isEqualTo(TradingMode.LIVE);
    }

    private Jwt jwt(String mode) {
        Map<String, Object> claims = new java.util.LinkedHashMap<>();
        claims.put("sub", "10");
        claims.put("companyId", 1L);
        if (mode != null) claims.put("tradingMode", mode);
        claims.put("identityType", "DEMO".equals(mode) ? "INTERNAL" : "CLIENT");
        claims.put("accessMode", "DEMO".equals(mode) ? "INTERNAL_DEMO" : "CLIENT_SELF");
        return new Jwt("token", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"), claims);
    }
}
