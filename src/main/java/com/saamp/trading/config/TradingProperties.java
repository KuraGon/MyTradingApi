package com.saamp.trading.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;

/** Central typed configuration for Trading API runtime rules and provider integration. */
@Component
@ConfigurationProperties(prefix = "trading")
public class TradingProperties {

    private final Security security = new Security();
    private final Pricing pricing = new Pricing();
    private final Reservations reservations = new Reservations();
    private final Provider provider = new Provider();
    private final Reconciliation reconciliation = new Reconciliation();

    public Security getSecurity() { return security; }
    public Pricing getPricing() { return pricing; }
    public Reservations getReservations() { return reservations; }
    public Provider getProvider() { return provider; }
    public Reconciliation getReconciliation() { return reconciliation; }

    public static class Security {
        private String issuer;
        private String audience = "MYTRADING";
        private String jwkSetUri;
        public String getIssuer() { return issuer; }
        public void setIssuer(String issuer) { this.issuer = issuer; }
        public String getAudience() { return audience; }
        public void setAudience(String audience) { this.audience = audience; }
        public String getJwkSetUri() { return jwkSetUri; }
        public void setJwkSetUri(String jwkSetUri) { this.jwkSetUri = jwkSetUri; }
    }

    public static class Pricing {
        private Duration executionMaxAge = Duration.ofSeconds(30);
        private Duration displayMaxAge = Duration.ofMinutes(5);
        private BigDecimal defaultDriftTolerance = new BigDecimal("0.002000");
        public Duration getExecutionMaxAge() { return executionMaxAge; }
        public void setExecutionMaxAge(Duration executionMaxAge) { this.executionMaxAge = executionMaxAge; }
        public Duration getDisplayMaxAge() { return displayMaxAge; }
        public void setDisplayMaxAge(Duration displayMaxAge) { this.displayMaxAge = displayMaxAge; }
        public BigDecimal getDefaultDriftTolerance() { return defaultDriftTolerance; }
        public void setDefaultDriftTolerance(BigDecimal defaultDriftTolerance) { this.defaultDriftTolerance = defaultDriftTolerance; }
    }

    public static class Reservations {
        private Duration ttl = Duration.ofMinutes(2);
        private Duration expiryScanDelay = Duration.ofSeconds(30);
        public Duration getTtl() { return ttl; }
        public void setTtl(Duration ttl) { this.ttl = ttl; }
        public Duration getExpiryScanDelay() { return expiryScanDelay; }
        public void setExpiryScanDelay(Duration expiryScanDelay) { this.expiryScanDelay = expiryScanDelay; }
    }

    public static class Reconciliation {
        private boolean enabled = false;
        private Duration interval = Duration.ofMinutes(15);
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Duration getInterval() { return interval; }
        public void setInterval(Duration interval) { this.interval = interval; }
    }

    public static class Provider {
        private String mode = "SIMULATED";
        private final Pmx pmx = new Pmx();
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public Pmx getPmx() { return pmx; }

        public static class Pmx {
            private String baseUrl;
            private String tokenId;
            private String version = "v1_3";
            private String environment;
            private Duration requestTimeout = Duration.ofSeconds(15);
            private Duration tokenExpiryWarning = Duration.ofDays(28);
            private Duration tokenExpiryCheckInterval = Duration.ofHours(24);
            public String getBaseUrl() { return baseUrl; }
            public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
            public String getTokenId() { return tokenId; }
            public void setTokenId(String tokenId) { this.tokenId = tokenId; }
            public String getVersion() { return version; }
            public void setVersion(String version) { this.version = version; }
            public String getEnvironment() { return environment; }
            public void setEnvironment(String environment) { this.environment = environment; }
            public Duration getRequestTimeout() { return requestTimeout; }
            public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
            public Duration getTokenExpiryWarning() { return tokenExpiryWarning; }
            public void setTokenExpiryWarning(Duration tokenExpiryWarning) { this.tokenExpiryWarning = tokenExpiryWarning; }
            public Duration getTokenExpiryCheckInterval() { return tokenExpiryCheckInterval; }
            public void setTokenExpiryCheckInterval(Duration tokenExpiryCheckInterval) { this.tokenExpiryCheckInterval = tokenExpiryCheckInterval; }
        }
    }
}
