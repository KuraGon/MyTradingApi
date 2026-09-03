package com.saamp.trading.security;

import java.util.Set;

/** Identity snapshot extracted from a validated MyPortal access token. */
public record CurrentTrader(long userId, long companyId, String companyCode, String username, Set<String> permissions) {
}
