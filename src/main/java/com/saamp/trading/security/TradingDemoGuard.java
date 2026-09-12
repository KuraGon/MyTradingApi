package com.saamp.trading.security;

import com.saamp.trading.common.TradingException;
import com.saamp.trading.config.TradingProperties;
import com.saamp.trading.domain.TradingMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Enforces the explicit DEMO JWT context independently from any front-end behaviour.
 */
@Component
public class TradingDemoGuard {
    private final TradingProperties properties;
    private final Environment environment;

    public TradingDemoGuard(TradingProperties properties) {
        this(properties, null);
    }

    @Autowired
    public TradingDemoGuard(TradingProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    public TradingMode resolveMode(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return TradingMode.LIVE;
        }
        String raw = jwt.getClaimAsString("tradingMode");
        TradingMode mode;
        if (raw == null || raw.isBlank()) {
            mode = TradingMode.LIVE;
        } else {
            try {
                mode = TradingMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException invalid) {
                throw failure("INVALID_TOKEN_SCOPE", "Mode MyTrading invalide dans le jeton.");
            }
        }
        String access = jwt.getClaimAsString("accessMode");
        String identity = jwt.getClaimAsString("identityType");
        if ("INTERNAL_DELEGATED".equals(access)) {
            throw failure("INTERNAL_DELEGATED_NOT_ENABLED", "La delegation interne n'est pas activee.");
        }
        if (mode == TradingMode.DEMO && (!"INTERNAL_DEMO".equals(access) || !"INTERNAL".equals(identity))) {
            throw failure("INVALID_TOKEN_SCOPE", "Contexte DEMO interne explicite requis.");
        }
        if (mode == TradingMode.LIVE && ((access != null && !"CLIENT_SELF".equals(access))
                || (identity != null && !"CLIENT".equals(identity)))) {
            throw failure("INVALID_TOKEN_SCOPE", "Contexte LIVE client requis en V1.");
        }
        if (mode == TradingMode.DEMO) {
            assertDemoRuntimeAllowed();
        }
        return mode;
    }

    /** Rejects a DEMO request before the order can reach a non-simulated provider. */
    public void assertSubmissionAllowed(TradingMode mode) {
        if (mode != TradingMode.DEMO) {
            return;
        }
        assertDemoRuntimeAllowed();
    }

    /**
     * Second barrier at the PMX boundary. It protects against a future controller or
     * service path that would forget to carry the trader mode.
     */
    public void assertRealProviderSubmissionAllowed() {
        if (resolveMode(SecurityContextHolder.getContext().getAuthentication()) == TradingMode.DEMO) {
            throw failure("DEMO_REAL_PROVIDER_FORBIDDEN",
                    "Un compte de démonstration ne peut pas transmettre d'ordre à un provider réel.");
        }
    }

    private static TradingException failure(String code, String message) {
        return new TradingException(HttpStatus.FORBIDDEN, code, message);
    }

    private void assertDemoRuntimeAllowed() {
        if (!properties.getDemo().isEnabled()) {
            throw failure("DEMO_DISABLED", "Les jetons MyTrading DEMO ne sont pas autorisés sur cet environnement.");
        }
    }
}
