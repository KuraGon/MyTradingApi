package com.saamp.trading.account;

import com.saamp.trading.common.TradingException;
import com.saamp.trading.common.TroyWeightConverter;
import com.saamp.trading.domain.AccountStatus;
import com.saamp.trading.domain.Asset;
import com.saamp.trading.pricing.ClientQuote;
import com.saamp.trading.pricing.PricingService;
import com.saamp.trading.risk.MarginRateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PositionServiceTest {
    private final BalanceRepository balances = mock(BalanceRepository.class);
    private final PricingService pricing = mock(PricingService.class);
    private final MarginRateRepository marginRates = mock(MarginRateRepository.class);
    private final PositionService service = new PositionService(balances, pricing, marginRates);
    private final TradingAccount account = new TradingAccount(10L, 42L, Asset.EUR, AccountStatus.ACTIVE,
            null, null, null, 1, OffsetDateTime.now(), OffsetDateTime.now());

    @Test
    void valuesLongAndShortPositionsWithClientPricesOnly() {
        when(balances.findAll(10L)).thenReturn(List.of(balance(Asset.EUR,"1000"),
                balance(Asset.XAU,"2.000000"),balance(Asset.XAG,"-3.000000")));
        when(pricing.quoteForDisplay(42L, Asset.XAU, Asset.EUR)).thenReturn(quote(Asset.XAU, "90.00", "110.00"));
        when(pricing.quoteForDisplay(42L, Asset.XAG, Asset.EUR)).thenReturn(quote(Asset.XAG, "9.00", "11.00"));
        when(marginRates.currentRate(10L,Asset.XAU)).thenReturn(new BigDecimal("0.05"));
        when(marginRates.currentRate(10L,Asset.XAG)).thenReturn(new BigDecimal("0.07"));

        var result = service.read(account);

        assertThat(result).containsExactly(
                new AccountPosition(Asset.XAU, new BigDecimal("2.000000"), new BigDecimal("90.00"),
                        new BigDecimal("180.00"), result.get(0).priceAsOf(),new BigDecimal("5.00"),new BigDecimal("9.00")),
                new AccountPosition(Asset.XAG, new BigDecimal("-3.000000"), new BigDecimal("11.00"),
                        new BigDecimal("-33.00"), result.get(1).priceAsOf(),new BigDecimal("7.00"),new BigDecimal("2.31")));
        verify(balances).findAll(10L);
        verifyNoMoreInteractions(balances);
    }

    @Test
    void exactLongXauUsesClientSellAndPublishesFivePercentMargin() {
        when(balances.findAll(10L)).thenReturn(List.of(balance(Asset.XAU,"1.010000")));
        when(pricing.quoteForDisplay(42L,Asset.XAU,Asset.EUR)).thenReturn(quote(Asset.XAU,"2991","3019.03"));
        when(marginRates.currentRate(10L,Asset.XAU)).thenReturn(new BigDecimal("0.0500"));

        var position=service.read(account).getFirst();

        assertThat(position.clientPrice()).isEqualByComparingTo("2991");
        assertThat(position.valuation()).isEqualTo(new BigDecimal("3020.91"));
        assertThat(position.marginRatePct()).isEqualTo(new BigDecimal("5.00"));
        assertThat(position.marginRequirement()).isEqualTo(new BigDecimal("151.05"));
        assertThat(position.clientPrice().multiply(new BigDecimal("1000"))
                .divide(TroyWeightConverter.GRAMS_PER_TROY_OUNCE,2,RoundingMode.HALF_UP))
                .isEqualTo(new BigDecimal("96162.88"));
        verify(pricing).quoteForDisplay(42L,Asset.XAU,Asset.EUR);
        verifyNoMoreInteractions(pricing);
    }

    @Test
    void shortXauUsesClientBuyAndKeepsValuationNegative() {
        when(balances.findAll(10L)).thenReturn(List.of(balance(Asset.XAU,"-1.010000")));
        when(pricing.quoteForDisplay(42L,Asset.XAU,Asset.EUR)).thenReturn(quote(Asset.XAU,"2991","3019.03"));
        when(marginRates.currentRate(10L,Asset.XAU)).thenReturn(new BigDecimal("0.0500"));

        var position=service.read(account).getFirst();

        assertThat(position.clientPrice()).isEqualByComparingTo("3019.03");
        assertThat(position.valuation()).isEqualTo(new BigDecimal("-3049.22"));
        assertThat(position.marginRequirement()).isEqualTo(new BigDecimal("152.46"));
    }

    @Test
    void usesConfiguredRateWithoutRoundingItToFivePercent() {
        when(balances.findAll(10L)).thenReturn(List.of(balance(Asset.XAU,"1")));
        when(pricing.quoteForDisplay(42L,Asset.XAU,Asset.EUR)).thenReturn(quote(Asset.XAU,"100","110"));
        when(marginRates.currentRate(10L,Asset.XAU)).thenReturn(new BigDecimal("0.1234"));
        var position=service.read(account).getFirst();
        assertThat(position.marginRatePct()).isEqualTo(new BigDecimal("12.34"));
        assertThat(position.marginRequirement()).isEqualTo(new BigDecimal("12.34"));
    }

    @Test
    void keepsQuantityAndPricePrecisionUntilPublicationThenReusesPublishedValue() {
        when(balances.findAll(10L)).thenReturn(List.of(balance(Asset.XAU,"0.123456")));
        when(pricing.quoteForDisplay(42L,Asset.XAU,Asset.EUR)).thenReturn(quote(Asset.XAU,"0.777604","0.8"));
        when(marginRates.currentRate(10L,Asset.XAU)).thenReturn(new BigDecimal("0.05"));

        var position=service.read(account).getFirst();

        assertThat(position.valuation()).isEqualTo(new BigDecimal("0.10"));
        assertThat(position.marginRequirement()).isEqualTo(new BigDecimal("0.01"));
        assertThat(position.clientPrice()).isEqualTo(new BigDecimal("0.777604"));
        assertThat(position.quantityOz()).isEqualTo(new BigDecimal("0.123456"));
    }

    @Test
    void ignoresZeroMetalAndCurrenciesWithoutRequestingQuotesOrMargin() {
        when(balances.findAll(10L)).thenReturn(List.of(balance(Asset.EUR,"100"),balance(Asset.USD,"50"),
                balance(Asset.XAU,"0")));
        assertThat(service.read(account)).isEmpty();
        verifyNoInteractions(pricing,marginRates);
    }

    @Test
    void propagatesMarketPriceStale() {
        when(balances.findAll(10L)).thenReturn(List.of(balance(Asset.XAU,"1")));
        when(pricing.quoteForDisplay(42L, Asset.XAU, Asset.EUR))
                .thenThrow(new TradingException(HttpStatus.CONFLICT, "MARKET_PRICE_STALE", "stale"));
        assertThatThrownBy(() -> service.read(account))
                .isInstanceOfSatisfying(TradingException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("MARKET_PRICE_STALE"));
    }

    @Test
    void refusesMissingConfiguredMarginInsteadOfInventingRate() {
        when(balances.findAll(10L)).thenReturn(List.of(balance(Asset.XAU,"1")));
        when(pricing.quoteForDisplay(42L,Asset.XAU,Asset.EUR)).thenReturn(quote(Asset.XAU,"2991","3019.03"));
        when(marginRates.currentRate(10L,Asset.XAU))
                .thenThrow(new TradingException(HttpStatus.CONFLICT,"MARGIN_RATE_MISSING","missing"));
        assertThatThrownBy(()->service.read(account)).isInstanceOfSatisfying(TradingException.class,
                exception->assertThat(exception.getCode()).isEqualTo("MARGIN_RATE_MISSING"));
    }

    private Balance balance(Asset asset,String quantity) {
        return new Balance(10L,asset,new BigDecimal(quantity),OffsetDateTime.now());
    }

    private ClientQuote quote(Asset asset, String sellPrice, String buyPrice) {
        return new ClientQuote(asset, asset.name() + "EUR", new BigDecimal("3000"), new BigDecimal("3010"),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(buyPrice), new BigDecimal(sellPrice),
                BigDecimal.ZERO, BigDecimal.ZERO, 1, OffsetDateTime.parse("2026-09-03T10:00:00Z"));
    }
}
