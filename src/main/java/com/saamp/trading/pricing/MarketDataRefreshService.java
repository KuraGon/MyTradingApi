package com.saamp.trading.pricing;

import com.saamp.trading.common.TradingException;
import com.saamp.trading.provider.MarketQuote;
import com.saamp.trading.provider.TradingProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/** Pulls raw provider quotes server-side and stores them locally; raw prices never cross the client API boundary. */
@Service
public class MarketDataRefreshService {
    private final TradingProvider provider;
    private final MarketPriceService prices;

    public MarketDataRefreshService(TradingProvider provider, MarketPriceService prices) {
        this.provider = provider;
        this.prices = prices;
    }

    /** Refreshes one required pair. PMXConnect internally reads both MTL and FOR to keep one coherent snapshot. */
    public void refresh(String pair) {
        List<MarketQuote> quotes = provider.fetchSpotRates(Set.of());
        MarketQuote quote = quotes.stream().filter(q -> pair.equalsIgnoreCase(q.pair())).findFirst()
                .orElseThrow(() -> new TradingException(HttpStatus.SERVICE_UNAVAILABLE,
                        "MARKET_PRICE_MISSING", "Le fournisseur n'a pas retourné de prix pour " + pair));
        // The PMX provider returns the merged MTL+FOR snapshot. Store the complete snapshot,
        // not only the requested pair, so FX valuation is available from the same price_as_of.
        quotes.forEach(this::store);
    }

    /**
     * Refreshes the complete provider snapshot. For PMXConnect this merges GetSpotRates/MTL and
     * GetSpotRates/FOR; all returned quotes share the same provider reference timestamp.
     */
    public int refreshAll() {
        List<MarketQuote> quotes = provider.fetchSpotRates(Set.of());
        quotes.forEach(this::store);
        return quotes.size();
    }

    private void store(MarketQuote quote) {
        OffsetDateTime asOf = quote.asOf() == null ? OffsetDateTime.now() : quote.asOf();
        prices.store(new MarketPrice(quote.pair(), quote.bid(), quote.ask(), quote.mid(), asOf,
                provider.sourceName(), OffsetDateTime.now()));
    }
}
