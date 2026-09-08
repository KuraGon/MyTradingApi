package com.saamp.trading.provider;

import com.saamp.trading.domain.OrderSide;
import com.saamp.trading.pricing.MarketPrice;
import com.saamp.trading.pricing.PricingRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Protège la compatibilité des références simulées avec la synchronisation AS400. */
class SimulatedTradingProviderTest {
    @Test
    void simulatedExecutionsHaveDistinctNonBlankIdsWithinSiref3Width() {
        var pricing = mock(PricingRepository.class);
        when(pricing.findMarketPrice("XAUEUR")).thenReturn(Optional.of(new MarketPrice(
                "XAUEUR", new BigDecimal("2999"), new BigDecimal("3001"),
                new BigDecimal("3000"), null, "SIMULATED", null)));
        var provider = new SimulatedTradingProvider(pricing);
        var first = provider.submitSpotOrder(new SpotOrderRequest("order-1", "XAUEUR", OrderSide.BUY, BigDecimal.ONE));
        var second = provider.submitSpotOrder(new SpotOrderRequest("order-2", "XAUEUR", OrderSide.SELL, BigDecimal.ONE));

        assertThat(first.executionId()).isNotBlank().hasSizeLessThanOrEqualTo(20).matches("SIM-[A-Za-z0-9_-]{16}");
        assertThat(second.executionId()).isNotBlank().hasSizeLessThanOrEqualTo(20).matches("SIM-[A-Za-z0-9_-]{16}");
        assertThat(first.executionId()).isNotEqualTo(second.executionId());
        assertThat(first.state()).isEqualTo(AcknowledgementState.FILLED);
        assertThat(second.state()).isEqualTo(AcknowledgementState.FILLED);
    }
}
