package com.saamp.trading.common;

import com.saamp.trading.domain.Asset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TradingPairTest {
    @Test
    void buildsPmxPairsWithoutSeparators() {
        assertThat(TradingPair.metalAgainst(Asset.XAU, Asset.EUR)).isEqualTo("XAUEUR");
        assertThat(TradingPair.currencyPair(Asset.EUR, Asset.USD)).isEqualTo("EURUSD");
    }
}
