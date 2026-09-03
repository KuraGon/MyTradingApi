package com.saamp.trading.domain;

import java.util.EnumSet;
import java.util.Set;

/** Assets supported by the MyTrading V1 ledger. */
public enum Asset {
    EUR, USD, XAU, XAG, XPT, XPD;

    private static final Set<Asset> CURRENCIES = EnumSet.of(EUR, USD);
    private static final Set<Asset> METALS = EnumSet.of(XAU, XAG, XPT, XPD);

    public boolean isCurrency() {
        return CURRENCIES.contains(this);
    }

    public boolean isMetal() {
        return METALS.contains(this);
    }

    public static Set<Asset> metals() {
        return Set.copyOf(METALS);
    }
}
