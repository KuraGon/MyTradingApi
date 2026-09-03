package com.saamp.trading.common;

import com.saamp.trading.domain.Asset;

/** PMXConnect pair identifiers never contain separators (e.g. XAUEUR, EURUSD). */
public final class TradingPair {
    private TradingPair() {}

    public static String metalAgainst(Asset metal, Asset baseCurrency) {
        if (metal == null || !metal.isMetal()) throw new IllegalArgumentException("metal expected");
        if (baseCurrency == null || !baseCurrency.isCurrency()) throw new IllegalArgumentException("base currency expected");
        return metal.name() + baseCurrency.name();
    }

    public static String currencyPair(Asset baseCurrency, Asset foreignCurrency) {
        if (baseCurrency == null || !baseCurrency.isCurrency() || foreignCurrency == null || !foreignCurrency.isCurrency()) {
            throw new IllegalArgumentException("currency assets expected");
        }
        return baseCurrency.name() + foreignCurrency.name();
    }
}
