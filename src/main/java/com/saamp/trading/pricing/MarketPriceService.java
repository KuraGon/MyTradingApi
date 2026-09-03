package com.saamp.trading.pricing;

import com.saamp.trading.common.TradingException;
import com.saamp.trading.config.TradingProperties;
import com.saamp.trading.domain.Asset;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;

/** Reads cached market prices and enforces freshness before any sensitive decision. */
@Service
public class MarketPriceService {
    private final PricingRepository repository;
    private final TradingProperties properties;

    public MarketPriceService(PricingRepository repository, TradingProperties properties) {
        this.repository = repository; this.properties = properties;
    }

    public MarketPrice requireFreshForExecution(String pair) {
        MarketPrice price = repository.findMarketPrice(pair)
                .orElseThrow(() -> new TradingException(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_PRICE_MISSING", "Aucun prix disponible pour " + pair));
        ensureFresh(price, properties.getPricing().getExecutionMaxAge());
        return price;
    }

    public MarketPrice requireFreshForDisplay(String pair) {
        MarketPrice price = repository.findMarketPrice(pair)
                .orElseThrow(() -> new TradingException(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_PRICE_MISSING", "Aucun prix disponible pour " + pair));
        ensureFresh(price, properties.getPricing().getDisplayMaxAge());
        return price;
    }

    public void store(MarketPrice price) { repository.upsertMarketPrice(price); }

    private void ensureFresh(MarketPrice price, Duration maxAge) {
        Duration age = Duration.between(price.priceAsOf(), OffsetDateTime.now());
        if (age.isNegative() || age.compareTo(maxAge) > 0) {
            throw new TradingException(HttpStatus.CONFLICT, "MARKET_PRICE_STALE", "Prix de marché périmé pour " + price.pair());
        }
    }
}
