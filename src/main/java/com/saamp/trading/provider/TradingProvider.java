package com.saamp.trading.provider;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Market execution provider boundary. The domain never depends on PMXConnect/FIX DTOs.
 */
public interface TradingProvider {
    /** Stable source label stored with locally cached market prices. */
    default String sourceName() { return "TRADING_PROVIDER"; }

    /** Whether this runtime provider is safe and ready to transmit irreversible SPOT orders. */
    default boolean supportsSpotOrderSubmission() { return true; }

    List<MarketQuote> fetchSpotRates(Set<String> pairs);
    OrderAcknowledgement submitSpotOrder(SpotOrderRequest request);
    Optional<ExecutionReport> queryRequestStatus(String clientOrderId);
    List<ProviderPosition> fetchPositions();
}
