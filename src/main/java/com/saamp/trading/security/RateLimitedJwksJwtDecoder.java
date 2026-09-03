package com.saamp.trading.security;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import com.saamp.trading.config.TradingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local JWT verifier backed by an in-memory JWKS cache.
 *
 * <p>Keys are refreshed hourly. An unknown {@code kid} can trigger one early refresh at most
 * every five minutes, preventing forged tokens from turning MyPortal into a network oracle.</p>
 */
@Component
public class RateLimitedJwksJwtDecoder implements JwtDecoder {
    private static final Logger log = LoggerFactory.getLogger(RateLimitedJwksJwtDecoder.class);
    private static final Duration UNKNOWN_KID_MIN_REFRESH = Duration.ofMinutes(5);

    private final TradingProperties properties;
    private final RestClient restClient;
    private final AtomicReference<Map<String, JwtDecoder>> decoders = new AtomicReference<>(Map.of());
    private final Object refreshLock = new Object();
    private volatile Instant lastUnknownKidRefresh = Instant.EPOCH;

    public RateLimitedJwksJwtDecoder(TradingProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.create();
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        final String kid;
        try {
            kid = SignedJWT.parse(token).getHeader().getKeyID();
        } catch (Exception e) {
            throw new JwtException("JWT header is invalid", e);
        }
        if (kid == null || kid.isBlank()) throw new JwtException("JWT kid is required");

        JwtDecoder decoder = decoders.get().get(kid);
        if (decoder == null) {
            maybeRefreshForUnknownKid();
            decoder = decoders.get().get(kid);
        }
        if (decoder == null) throw new JwtException("Unknown JWT kid");
        return decoder.decode(token);
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 0)
    public void scheduledRefresh() {
        try {
            refresh();
        } catch (RuntimeException e) {
            if (decoders.get().isEmpty()) {
                log.error("Initial MyPortal JWKS load failed: {}", e.getMessage());
            } else {
                log.warn("MyPortal JWKS refresh failed; keeping cached keys: {}", e.getMessage());
            }
        }
    }

    private void maybeRefreshForUnknownKid() {
        Instant now = Instant.now();
        if (Duration.between(lastUnknownKidRefresh, now).compareTo(UNKNOWN_KID_MIN_REFRESH) < 0) return;
        synchronized (refreshLock) {
            now = Instant.now();
            if (Duration.between(lastUnknownKidRefresh, now).compareTo(UNKNOWN_KID_MIN_REFRESH) < 0) return;
            lastUnknownKidRefresh = now;
            try {
                refresh();
            } catch (RuntimeException e) {
                log.warn("Early JWKS refresh for unknown kid failed; cached keys remain active: {}", e.getMessage());
            }
        }
    }

    private void refresh() {
        String body = restClient.get().uri(properties.getSecurity().getJwkSetUri()).retrieve().body(String.class);
        if (body == null || body.isBlank()) throw new IllegalStateException("Empty JWKS response");
        try {
            JWKSet jwkSet = JWKSet.parse(body);
            Map<String, JwtDecoder> next = new HashMap<>();
            for (JWK jwk : jwkSet.getKeys()) {
                if (!(jwk instanceof RSAKey rsa) || rsa.isPrivate() || rsa.getKeyID() == null) continue;
                RSAPublicKey publicKey = rsa.toRSAPublicKey();
                NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).signatureAlgorithm(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256).build();
                decoder.setJwtValidator(validators());
                next.put(rsa.getKeyID(), decoder);
            }
            if (next.isEmpty()) throw new IllegalStateException("JWKS contains no usable RSA public key");
            decoders.set(Map.copyOf(next));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to parse MyPortal JWKS", e);
        }
    }

    private OAuth2TokenValidator<Jwt> validators() {
        OAuth2TokenValidator<Jwt> standard = JwtValidators.createDefaultWithIssuer(properties.getSecurity().getIssuer());
        OAuth2TokenValidator<Jwt> audience = jwt -> {
            List<String> aud = jwt.getAudience();
            return aud != null && aud.contains(properties.getSecurity().getAudience())
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Audience MYTRADING required", null));
        };
        return new DelegatingOAuth2TokenValidator<>(standard, audience);
    }
}
