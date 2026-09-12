package com.saamp.trading.security;

import java.util.Set;
import java.util.function.Supplier;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/** Refuse par defaut toute route DEMO non explicitement compatible avec la projection locale. */
public final class DemoRouteAuthorization implements AuthorizationManager<RequestAuthorizationContext> {
    private static final String ACCOUNT = "/api/v1/accounts/me";
    private static final Set<String> READS = Set.of(ACCOUNT, ACCOUNT + "/balances", ACCOUNT + "/positions",
            ACCOUNT + "/summary", ACCOUNT + "/prices", ACCOUNT + "/orders", ACCOUNT + "/statement",
            "/api/v1/platform/status");
    private final Environment environment;

    /** @param environment runtime reel, prioritaire sur le contexte du token */
    public DemoRouteAuthorization(Environment environment) { this.environment = environment; }

    /** @param authentication appelant @param context requete @return decision fail-closed pour DEMO */
    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication, RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken)
            return new AuthorizationDecision(false);
        if (!(auth.getPrincipal() instanceof Jwt jwt)) return new AuthorizationDecision(true);
        String mode = jwt.getClaimAsString("tradingMode");
        if ("INTERNAL_DELEGATED".equals(jwt.getClaimAsString("accessMode")))
            return new AuthorizationDecision(false);
        if (mode == null || mode.isBlank() || "LIVE".equalsIgnoreCase(mode.trim()))
            return new AuthorizationDecision(true);
        if (!"DEMO".equalsIgnoreCase(mode.trim())
                || !environment.getProperty("trading.demo.enabled", Boolean.class, false)
                || !"INTERNAL_DEMO".equals(jwt.getClaimAsString("accessMode"))
                || !"INTERNAL".equals(jwt.getClaimAsString("identityType")))
            return new AuthorizationDecision(false);
        var request = context.getRequest();
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean allowed = "GET".equals(request.getMethod()) && (READS.contains(path)
                || path.matches(ACCOUNT + "/orders/[0-9]+"));
        allowed |= "POST".equals(request.getMethod()) && (path.equals(ACCOUNT + "/orders/preview")
                || path.matches(ACCOUNT + "/orders/[0-9]+/submit"));
        return new AuthorizationDecision(allowed);
    }
}
