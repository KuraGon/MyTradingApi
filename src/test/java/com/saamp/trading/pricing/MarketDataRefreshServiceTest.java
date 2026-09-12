package com.saamp.trading.pricing;

import com.saamp.trading.provider.MarketQuote;
import com.saamp.trading.provider.TradingProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MarketDataRefreshServiceTest {

    @Mock TradingProvider provider;
    @Mock MarketPriceService prices;

    @Test
    void singlePairRefreshUsesThePairAwareProviderPathOnce() {
        OffsetDateTime asOf = OffsetDateTime.parse("2026-08-20T12:00:00Z");
        when(provider.fetchSpotRates(Set.of("XAUEUR"))).thenReturn(List.of(
                new MarketQuote("XAUEUR", new BigDecimal("3825.493"), new BigDecimal("3826.856"), null, asOf)));

        new MarketDataRefreshService(provider, prices).refresh("XAUEUR");

        ArgumentCaptor<MarketPrice> captor = ArgumentCaptor.forClass(MarketPrice.class);
        verify(provider).fetchSpotRates(Set.of("XAUEUR"));
        verify(provider).sourceName();
        verify(prices).store(captor.capture());
        assertThat(captor.getAllValues()).extracting(MarketPrice::pair).containsExactly("XAUEUR");
        assertThat(captor.getAllValues()).allSatisfy(p -> assertThat(p.priceAsOf()).isEqualTo(asOf));
    }
}
