package com.saamp.trading.as400;

import com.saamp.trading.provider.TradingProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/** Lit uniquement les cours fournisseur ; n'appelle jamais une operation de trading. */
@Component
class As400FxSource {
    private final TradingProvider provider;
    private final String quoteField;

    As400FxSource(TradingProvider provider, @Value("${trading.as400.fx-quote-field:MID}") String quoteField) {
        this.provider=provider;
        this.quoteField=quoteField;
    }

    FrozenFx fetch() {
        if (!"MID".equals(quoteField))
            throw new IllegalStateException("AS400_FX_CONVENTION_INVALID");
        var quotes=provider.fetchSpotRates(Set.of("EURUSD")).stream()
                .filter(q -> "EURUSD".equalsIgnoreCase(q.pair())).toList();
        if (quotes.size()!=1) throw new IllegalStateException("AS400_FX_RATE_UNAVAILABLE");
        var quote=quotes.getFirst();
        // Le MID est volontairement neutre : le sens du trade ne choisit jamais BID ou ASK.
        BigDecimal raw=quote.mid();
        if (raw==null || raw.signum()<=0) throw new IllegalStateException("AS400_FX_RATE_UNAVAILABLE");
        BigDecimal rate=raw.setScale(2,RoundingMode.HALF_UP);
        if (rate.signum()<=0 || rate.compareTo(new BigDecimal("100"))>=0)
            throw new IllegalStateException("AS400_FX_RATE_UNAVAILABLE");
        return new FrozenFx(rate,provider.sourceName()+":"+quoteField);
    }

    record FrozenFx(BigDecimal rate, String source) {}
}
