package com.saamp.trading.as400;

import com.saamp.trading.domain.OrderSide;
import com.saamp.trading.provider.MarketQuote;
import com.saamp.trading.provider.TradingProvider;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static com.saamp.trading.as400.As400SyncFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class As400FxSourceTest {
    private final TradingProvider provider=mock(TradingProvider.class);

    @ParameterizedTest @EnumSource(OrderSide.class)
    void roundedMidPricesEveryUsdLegIndependentlyOfTradeSide(OrderSide side) {
        when(provider.fetchSpotRates(Set.of("EURUSD"))).thenReturn(List.of(new MarketQuote("EURUSD",
                new BigDecimal("1.16234"),new BigDecimal("1.18234"),new BigDecimal("1.17234"),OffsetDateTime.now())));
        when(provider.sourceName()).thenReturn("SIMULATED");
        var fx=new As400FxSource(provider,"MID").fetch();
        assertThat(fx.rate()).isEqualTo(new BigDecimal("1.17"));
        assertThat(fx.source()).isEqualTo("SIMULATED:MID");
        var legs=execution(side,"USD","EXID-123").build(event(As400SyncState.PENDING),MAPPING,
                fx.rate(),List.of(1,2,3,4));
        assertThat(legs).allSatisfy(m->assertThat(m.fx()).isEqualTo(new BigDecimal("1.17")));
        // Ces valeurs different de celles obtenues avec le MID brut 1.17234.
        assertThat(legs).extracting(SicouviMovement::quotation).containsExactly(
                new BigDecimal("1578.0000"),new BigDecimal("1539.0000"),
                new BigDecimal("1539.0000"),new BigDecimal("1539.0000"));
        verify(provider).fetchSpotRates(Set.of("EURUSD"));
        verify(provider).sourceName();
        verifyNoMoreInteractions(provider);
    }

    @Test void defaultsToValidatedMidConventionWithoutEnvironmentOverride() {
        when(provider.fetchSpotRates(Set.of("EURUSD"))).thenReturn(List.of(new MarketQuote("EURUSD",
                null,null,new BigDecimal("1.17234"),OffsetDateTime.now())));
        when(provider.sourceName()).thenReturn("SIMULATED");
        new ApplicationContextRunner().withBean(TradingProvider.class,()->provider)
                .withBean(As400FxSource.class).run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(As400FxSource.class).fetch().rate()).isEqualTo(new BigDecimal("1.17"));
                });
    }

    @Test void missingQuoteFailsWithoutInventedRate() {
        when(provider.fetchSpotRates(Set.of("EURUSD"))).thenReturn(List.of());
        assertThatThrownBy(()->new As400FxSource(provider,"MID").fetch()).hasMessage("AS400_FX_RATE_UNAVAILABLE");
    }

    @ParameterizedTest @ValueSource(strings={"","BID","ASK"})
    void refusesAnyConventionOtherThanValidatedMid(String field) {
        assertThatThrownBy(()->new As400FxSource(provider,field).fetch()).hasMessage("AS400_FX_CONVENTION_INVALID");
        verifyNoInteractions(provider);
    }

    @Test void roundingIsHalfUp() {
        when(provider.fetchSpotRates(Set.of("EURUSD"))).thenReturn(List.of(new MarketQuote("EURUSD",
                null,null,new BigDecimal("1.175"),OffsetDateTime.now())));
        when(provider.sourceName()).thenReturn("PROVIDER");
        assertThat(new As400FxSource(provider,"MID").fetch().rate()).isEqualTo(new BigDecimal("1.18"));
    }

    @Test void missingMidNeverFallsBackToBidOrAsk() {
        when(provider.fetchSpotRates(Set.of("EURUSD"))).thenReturn(List.of(new MarketQuote("EURUSD",
                BigDecimal.ONE,BigDecimal.ONE,null,OffsetDateTime.now())));
        assertThatThrownBy(()->new As400FxSource(provider,"MID").fetch()).hasMessage("AS400_FX_RATE_UNAVAILABLE");
    }
}
