package com.saamp.trading.as400;

import com.saamp.trading.provider.TradingProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.boot.convert.DurationStyle;

/** Lit uniquement les cours fournisseur ; n'appelle jamais une operation de trading. */
@Component
public class As400FxSource {
    private final TradingProvider provider;
    private final String quoteField;
    private final Duration maxAge;

    @Autowired
    public As400FxSource(TradingProvider provider, @Value("${trading.as400.fx-quote-field:MID}") String quoteField,
                         @Value("${trading.pricing.execution-max-age:30s}") String maxAge) {
        this(provider, quoteField, DurationStyle.detectAndParse(maxAge));
    }
    public As400FxSource(TradingProvider provider, String quoteField) {
        this(provider, quoteField, Duration.ofSeconds(30));
    }
    private As400FxSource(TradingProvider provider, String quoteField, Duration maxAge) {
        this.provider=provider; this.quoteField=quoteField; this.maxAge=maxAge;
    }

    public FrozenFx fetch() {
        return read(false);
    }
    public FrozenFx fetchValuation() {
        return read(true);
    }
    private FrozenFx read(boolean valuation) {
        if (!"MID".equals(quoteField))
            throw new IllegalStateException("AS400_FX_CONVENTION_INVALID");
        var quotes=provider.fetchSpotRates(Set.of("EURUSD")).stream()
                .filter(q -> "EURUSD".equalsIgnoreCase(q.pair())).toList();
        if (quotes.size()!=1) throw new IllegalStateException("AS400_FX_RATE_UNAVAILABLE");
        var quote=quotes.getFirst();
        if (quote.asOf()==null) throw new IllegalStateException("AS400_FX_RATE_UNAVAILABLE");
        var age=Duration.between(quote.asOf(), OffsetDateTime.now());
        if (age.isNegative() || age.compareTo(maxAge)>0) throw new IllegalStateException("AS400_FX_RATE_STALE");
        // Le MID est volontairement neutre : le sens du trade ne choisit jamais BID ou ASK.
        BigDecimal raw=quote.mid();
        if (raw==null || raw.signum()<=0) throw new IllegalStateException("AS400_FX_RATE_UNAVAILABLE");
        BigDecimal rate=raw.setScale(valuation ? 6 : 2,RoundingMode.HALF_UP);
        if (rate.signum()<=0 || rate.compareTo(new BigDecimal("100"))>=0)
            throw new IllegalStateException("AS400_FX_RATE_UNAVAILABLE");
        return new FrozenFx(rate,provider.sourceName()+":"+quoteField);
    }

    public record FrozenFx(BigDecimal rate, String source) {}
    @FunctionalInterface interface FxReader { FrozenFx read(); }
}
