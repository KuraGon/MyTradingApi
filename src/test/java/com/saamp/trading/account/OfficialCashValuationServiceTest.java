package com.saamp.trading.account;

import com.saamp.trading.as400.As400FxSource;
import com.saamp.trading.domain.Asset;
import com.saamp.trading.provider.MarketQuote;
import com.saamp.trading.provider.TradingProvider;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OfficialCashValuationServiceTest {
  @Test void convertsOfficialEurCashToUsdWithSixDecimalFx() {
    TradingProvider provider=mock(TradingProvider.class);
    when(provider.fetchSpotRates(Set.of("EURUSD"))).thenReturn(java.util.List.of(new MarketQuote("EURUSD",new BigDecimal("1.16"),new BigDecimal("1.18"),new BigDecimal("1.170000"),OffsetDateTime.now())));
    var fx=new As400FxSource(provider,"MID");
    var valued=OfficialCashValuationService.value(Map.of(Asset.EUR,new BigDecimal("100.00")),Asset.USD,fx);
    assertThat(valued.get(Asset.USD)).isEqualByComparingTo("117.00");
  }
  @Test void eurAccountIsUnchangedAndNoFxIsRead() {
    var fx=mock(As400FxSource.class);
    var balances=Map.of(Asset.EUR,new BigDecimal("-100.00"));
    assertThat(OfficialCashValuationService.value(balances,Asset.EUR,fx)).isEqualTo(balances);
    verifyNoInteractions(fx);
  }
}
