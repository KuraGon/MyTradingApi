package com.saamp.trading.security;

import com.saamp.trading.domain.TradingMode;
import java.util.Set;

/** Identity snapshot extracted from a validated MyPortal access token. */
public record CurrentTrader(long userId, long companyId, String companyCode, String username,
                            Set<String> permissions, TradingMode tradingMode, String identityType,
                            com.saamp.trading.domain.TradingAccessMode accessMode) {
    public CurrentTrader(long userId, long companyId, String companyCode, String username,
                         Set<String> permissions, TradingMode tradingMode) {
        this(userId,companyId,companyCode,username,permissions,tradingMode,
                tradingMode == TradingMode.DEMO ? "INTERNAL" : "CLIENT",
                tradingMode == TradingMode.DEMO ? com.saamp.trading.domain.TradingAccessMode.INTERNAL_DEMO
                        : com.saamp.trading.domain.TradingAccessMode.CLIENT_SELF);
    }
    public CurrentTrader(long userId, long companyId, String companyCode, String username, Set<String> permissions) {
        this(userId, companyId, companyCode, username, permissions, TradingMode.LIVE);
    }
}
