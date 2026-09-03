package com.saamp.trading.provider;

import com.saamp.trading.domain.OrderSide;
import com.saamp.trading.pricing.PricingRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Deterministic local provider for Lot 2.1/2.2 and end-to-end tests without StoneX. */
@Component
@ConditionalOnProperty(name = "trading.provider.mode", havingValue = "SIMULATED", matchIfMissing = true)
public class SimulatedTradingProvider implements TradingProvider {
    private final PricingRepository pricing;
    public SimulatedTradingProvider(PricingRepository pricing) { this.pricing = pricing; }

    @Override public String sourceName() { return "SIMULATED"; }

    @Override
    public List<MarketQuote> fetchSpotRates(Set<String> pairs) {
        var marketPrices = pairs == null || pairs.isEmpty()
                ? pricing.findAllMarketPrices()
                : pricing.findMarketPrices(pairs.stream().sorted().toList());
        return marketPrices.stream()
                .map(p -> new MarketQuote(p.pair(), p.bid(), p.ask(), p.mid(), p.priceAsOf())).toList();
    }

    @Override
    public OrderAcknowledgement submitSpotOrder(SpotOrderRequest request) {
        var market = pricing.findMarketPrice(request.pair()).orElseThrow();
        var rate = request.side() == OrderSide.BUY ? market.ask() : market.bid();
        return new OrderAcknowledgement(AcknowledgementState.FILLED, request.clientOrderId(),
                "SIM-" + UUID.randomUUID(), rate, null, null);
    }

    @Override public Optional<ExecutionReport> queryRequestStatus(String clientOrderId) { return Optional.empty(); }
    @Override public List<ProviderPosition> fetchPositions() { return List.of(); }
}
