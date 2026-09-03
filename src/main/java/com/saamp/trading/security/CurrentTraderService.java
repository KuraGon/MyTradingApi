package com.saamp.trading.security;

import com.saamp.trading.common.TradingException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Resolves the authenticated MyTrading client from the standard Spring Security JWT principal. */
@Service
public class CurrentTraderService {

    public CurrentTrader current(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new TradingException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentification requise");
        }
        String sub = jwt.getSubject();
        Number companyId = jwt.getClaim("companyId");
        if (sub == null || companyId == null) {
            throw new TradingException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN_SCOPE", "Jeton sans société cliente");
        }
        List<String> permissions = jwt.getClaimAsStringList("permissions");
        return new CurrentTrader(
                Long.parseLong(sub),
                companyId.longValue(),
                jwt.getClaimAsString("companyCode"),
                jwt.getClaimAsString("username"),
                permissions == null ? Set.of() : Set.copyOf(new HashSet<>(permissions)));
    }
}
