package com.saamp.trading.pricing;

import com.saamp.trading.domain.*;
import java.math.*;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AbsoluteSpreadTest {
    private static BigDecimal b(String n) { return new BigDecimal(n); }
    @ParameterizedTest @CsvSource({"0.10,70.20,69.90","0.40,70.50,69.60","1.00,71.10,69.00","0,70.10,70.00"})
    void absoluteAddsOrSubtractsWithoutScalingTheMarket(String spread,String buy,String sell) {
        var value=new SpreadValue(SpreadType.ABSOLUTE,b(spread),"OZ");
        assertThat(PriceMath.applySpread(b("70.10"),value,OrderSide.BUY)).isEqualByComparingTo(buy);
        assertThat(PriceMath.applySpread(b("70.00"),value,OrderSide.SELL)).isEqualByComparingTo(sell);
    }
    @ParameterizedTest @CsvSource({"BUY,100.001,0.003","SELL,100.009,0.003","BUY,3456.123456,0.123456","SELL,0.000123,0.999999"})
    void historicalRatiosRetainExactDecimalResults(OrderSide side,String market,String value) {
        BigDecimal expected=b(market).multiply(side==OrderSide.BUY?BigDecimal.ONE.add(b(value)):BigDecimal.ONE.subtract(b(value)));
        assertThat(PriceMath.applySpread(b(market),new SpreadValue(SpreadType.PERCENTAGE,b(value),"OZ"),side)).isEqualTo(expected);
    }
    @Test void invalidUnitAmountAndResultFailClosed() {
        assertThatThrownBy(()->new SpreadValue(SpreadType.ABSOLUTE,b("0.1"),"KG")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new SpreadValue(SpreadType.ABSOLUTE,b("-0.1"),"OZ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new SpreadValue(SpreadType.PERCENTAGE,b("1"),"OZ")).isInstanceOf(IllegalArgumentException.class);
        for(String value:new String[]{"70","71"}) assertThatThrownBy(()->PriceMath.applySpread(b("70"),new SpreadValue(SpreadType.ABSOLUTE,b(value),"OZ"),OrderSide.SELL)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PriceMath.applySpreadRounded(b("0.0000001"),new SpreadValue(SpreadType.ABSOLUTE,BigDecimal.ZERO,"OZ"),OrderSide.SELL,6)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void absoluteRoundingPreservesSaampConvention() {
        var v=new SpreadValue(SpreadType.ABSOLUTE,b("0.100001"),"OZ");
        assertThat(PriceMath.applySpreadRounded(b("70.000001"),v,OrderSide.BUY,2)).isEqualTo(b("70.11"));
        assertThat(PriceMath.applySpreadRounded(b("70"),v,OrderSide.SELL,2)).isEqualTo(b("69.89"));
    }
    @ParameterizedTest @CsvSource({"XAG,EUR,0.10","XAG,USD,0.10","XAU,EUR,0.40","XAU,USD,0.40","XPT,EUR,1.00","XPD,USD,1.00"})
    void oneConfigurationAppliesNumericallyInEitherQuoteCurrency(Asset asset,Asset currency,String value) {
        var repository=mock(PricingRepository.class);var prices=mock(MarketPriceService.class);var refresh=mock(MarketDataRefreshService.class);
        String pair=asset.name()+currency.name();var now=OffsetDateTime.now();
        when(prices.requireFreshForDisplay(pair)).thenReturn(new MarketPrice(pair,b("70"),b("70.10"),b("70.05"),now,"PMXCONNECT",now));
        when(repository.findCurrentSpread(eq(3L),eq(asset),any())).thenReturn(Optional.of(new SpreadConfig(1,3,asset,b(value),b(value),1,now,null,SpreadType.ABSOLUTE,"OZ")));
        when(repository.findAssetConfig(asset)).thenReturn(Optional.of(new AssetConfig(asset,b("0.000001"),6,b("0.002"),true)));
        var quote=new PricingService(repository,prices,refresh).quoteForDisplay(3,asset,currency);
        assertThat(quote.pair()).isEqualTo(pair);assertThat(quote.spreadType()).isEqualTo(SpreadType.ABSOLUTE);
        assertThat(quote.clientBuyPrice()).isEqualByComparingTo(b("70.10").add(b(value)));
        assertThat(quote.clientSellPrice()).isEqualByComparingTo(b("70").subtract(b(value)));
        verifyNoInteractions(refresh);
    }
    @Test void aQuoteFromAnotherPairCannotBeAppliedToTheAccount() {
        var repository=mock(PricingRepository.class);var prices=mock(MarketPriceService.class);
        when(prices.requireFreshForDisplay("XAGEUR")).thenReturn(new MarketPrice("XAGUSD",b("70"),b("71"),b("70.5"),OffsetDateTime.now(),"PMXCONNECT",OffsetDateTime.now()));
        assertThatThrownBy(()->new PricingService(repository,prices,mock(MarketDataRefreshService.class)).quoteForDisplay(3,Asset.XAG,Asset.EUR))
                .isInstanceOf(com.saamp.trading.common.TradingException.class).hasMessageContaining("Paire");
    }
}
