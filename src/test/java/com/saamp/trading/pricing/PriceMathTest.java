package com.saamp.trading.pricing;

import com.saamp.trading.domain.OrderSide;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class PriceMathTest {
    @Test void buySpreadAndRoundingAreFavourableToSaamp() {
        assertThat(PriceMath.clientPrice(new BigDecimal("100.001"), new BigDecimal("0.003"), OrderSide.BUY, 2))
                .isEqualByComparingTo("100.31");
    }
    @Test void sellSpreadAndRoundingAreFavourableToSaamp() {
        assertThat(PriceMath.clientPrice(new BigDecimal("100.009"), new BigDecimal("0.003"), OrderSide.SELL, 2))
                .isEqualByComparingTo("99.70");
    }
    @Test void driftIsAbsoluteRatioAgainstDisplayedPrice() {
        assertThat(PriceMath.driftRatio(new BigDecimal("100"), new BigDecimal("100.20")))
                .isEqualByComparingTo("0.002000000000");
    }
}
