package com.saamp.trading.pricing;

import com.saamp.trading.domain.OrderSide;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Pure pricing rules from MyTrading Lot 2. */
public final class PriceMath {

    private static final BigDecimal ONE = BigDecimal.ONE;

    private PriceMath() {
    }

    public static BigDecimal rawClientPrice(BigDecimal marketPrice, BigDecimal spread, OrderSide side) {
        if (marketPrice == null || marketPrice.signum() <= 0) {
            throw new IllegalArgumentException("marketPrice must be positive");
        }
        if (spread == null || spread.signum() < 0 || spread.compareTo(ONE) >= 0) {
            throw new IllegalArgumentException("spread must be in [0,1)");
        }
        return side == OrderSide.BUY
                ? marketPrice.multiply(ONE.add(spread))
                : marketPrice.multiply(ONE.subtract(spread));
    }

    public static BigDecimal clientPrice(BigDecimal marketPrice, BigDecimal spread, OrderSide side, int scale) {
        BigDecimal raw = rawClientPrice(marketPrice, spread, side);
        // Favourable-to-SAAMP rounding: BUY rounds up, SELL rounds down.
        return raw.setScale(scale, side == OrderSide.BUY ? RoundingMode.CEILING : RoundingMode.FLOOR);
    }

    public static BigDecimal driftRatio(BigDecimal displayedClientPrice, BigDecimal freshClientPrice) {
        if (displayedClientPrice == null || displayedClientPrice.signum() <= 0 || freshClientPrice == null) {
            throw new IllegalArgumentException("prices are required and displayed price must be positive");
        }
        return freshClientPrice.subtract(displayedClientPrice).abs()
                .divide(displayedClientPrice, 12, RoundingMode.HALF_UP);
    }
}
