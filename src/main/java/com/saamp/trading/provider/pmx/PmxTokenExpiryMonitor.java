package com.saamp.trading.provider.pmx;

import com.saamp.trading.config.TradingProperties;
import com.saamp.trading.order.ExecutionGateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** Periodically warns before PMXConnect TokenID expiry so rotation is not dependent on an application restart. */
@Component
@ConditionalOnProperty(name = "trading.provider.mode", havingValue = "PMXCONNECT")
public class PmxTokenExpiryMonitor {
    private static final Logger log = LoggerFactory.getLogger(PmxTokenExpiryMonitor.class);

    private final TradingProperties.Provider.Pmx config;
    private final ExecutionGateRepository gate;
    private final PmxConnectJson json = new PmxConnectJson();

    public PmxTokenExpiryMonitor(TradingProperties properties, ExecutionGateRepository gate) {
        this.config = properties.getProvider().getPmx();
        this.gate = gate;
    }

    @Scheduled(fixedDelayString = "${trading.provider.pmx.token-expiry-check-interval:24h}")
    public void check() {
        PmxTokenInfo info = PmxTokenInspector.inspect(config.getTokenId(), json);
        Instant now = Instant.now();
        if (info.expiresAt() == null || !info.expiresAt().isAfter(now)) {
            gate.close("PMXCONNECT_TOKEN_EXPIRED");
            log.error("PMXConnect TokenID expired or has no usable exp claim; execution gate closed");
            return;
        }
        if (info.environment() == null || !info.environment().equalsIgnoreCase(config.getEnvironment())) {
            gate.close("PMXCONNECT_ENVIRONMENT_MISMATCH");
            log.error("PMXConnect TokenID environment mismatch; execution gate closed");
            return;
        }
        Duration remaining = Duration.between(now, info.expiresAt());
        if (remaining.compareTo(config.getTokenExpiryWarning()) <= 0) {
            log.warn("PMXConnect TokenID rotation required: {} days remaining, expiresAt={}",
                    remaining.toDays(), info.expiresAt());
        }
    }
}
