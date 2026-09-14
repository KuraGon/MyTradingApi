package com.saamp.trading.account;

import com.saamp.trading.as400.As400FxSource;
import com.saamp.trading.domain.Asset;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.Map;

/** Converts official accounting cash to the account currency for valuation only. */
public final class OfficialCashValuationService {
    private OfficialCashValuationService() {}
    public static Map<Asset, java.math.BigDecimal> value(Map<Asset, java.math.BigDecimal> balances,
                                                          Asset baseCurrency,
                                                          As400FxSource fx) {
        if (baseCurrency == Asset.EUR) return balances;
        if (baseCurrency != Asset.USD) throw new IllegalStateException("OFFICIAL_CURRENCY_UNSUPPORTED");
        var rate = fx.fetchValuation().rate();
        var out = new EnumMap<Asset, java.math.BigDecimal>(Asset.class);
        out.putAll(balances);
        var eur = balances.get(Asset.EUR);
        if (eur == null) throw new IllegalStateException("OFFICIAL_CASH_UNAVAILABLE");
        out.remove(Asset.EUR);
        out.put(Asset.USD, eur.multiply(rate).setScale(2, RoundingMode.HALF_UP));
        return out;
    }
}
