package com.saamp.trading.account;

import com.saamp.trading.common.TradingException;
import com.saamp.trading.domain.AccountStatus;
import com.saamp.trading.domain.Asset;
import com.saamp.trading.pricing.ClientQuote;
import com.saamp.trading.pricing.PricingService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PositionServiceTest {

    private final BalanceRepository balances = mock(BalanceRepository.class);
    private final PricingService pricing = mock(PricingService.class);
    private final PositionService service = new PositionService(balances, pricing);
    private final TradingAccount account = new TradingAccount(10L, 42L, Asset.EUR, AccountStatus.ACTIVE,
            null, null, null, 1, OffsetDateTime.now(), OffsetDateTime.now());

    @Test
    void valuesLongAndShortPositionsWithClientPricesOnly() {
        when(balances.findAll(10L)).thenReturn(List.of(
                new Balance(10L, Asset.EUR, new BigDecimal("1000.000000"), OffsetDateTime.now()),
                new Balance(10L, Asset.XAU, new BigDecimal("2.000000"), OffsetDateTime.now()),
                new Balance(10L, Asset.XAG, new BigDecimal("-3.000000"), OffsetDateTime.now())));
        when(pricing.quoteForDisplay(42L, Asset.XAU, Asset.EUR)).thenReturn(quote(Asset.XAU, "90.00", "110.00"));
        when(pricing.quoteForDisplay(42L, Asset.XAG, Asset.EUR)).thenReturn(quote(Asset.XAG, "9.00", "11.00"));

        var result = service.read(account);

        assertThat(result).containsExactly(
                new AccountPosition(Asset.XAU, new BigDecimal("2.000000"), new BigDecimal("90.00"),
                        new BigDecimal("180.00"), result.get(0).priceAsOf()),
                new AccountPosition(Asset.XAG, new BigDecimal("-3.000000"), new BigDecimal("11.00"),
                        new BigDecimal("-33.00"), result.get(1).priceAsOf()));
    }

    @Test
    void propagatesMarketPriceStale() {
        when(balances.findAll(10L)).thenReturn(List.of(
                new Balance(10L, Asset.XAU, BigDecimal.ONE, OffsetDateTime.now())));
        when(pricing.quoteForDisplay(42L, Asset.XAU, Asset.EUR))
                .thenThrow(new TradingException(HttpStatus.CONFLICT, "MARKET_PRICE_STALE", "stale"));

        assertThatThrownBy(() -> service.read(account))
                .isInstanceOfSatisfying(TradingException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("MARKET_PRICE_STALE"));
    }

    private ClientQuote quote(Asset asset, String sellPrice, String buyPrice) {
        return new ClientQuote(asset, asset.name() + "EUR", BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(buyPrice), new BigDecimal(sellPrice),
                BigDecimal.ZERO, BigDecimal.ZERO, 1, OffsetDateTime.parse("2026-09-03T10:00:00Z"));
    }
}
