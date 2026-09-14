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
        return applySpread(marketPrice, new SpreadValue(SpreadType.PERCENTAGE, spread, "OZ"), side);
    }

    /** @param marketPrice bid/ask dans la devise de la paire @param spread valeur typée @param side sens client
     * @return prix brut reproductible @throws IllegalArgumentException si le prix résultant est non positif */
    public static BigDecimal applySpread(BigDecimal marketPrice, SpreadValue spread, OrderSide side) {
        if (marketPrice == null || marketPrice.signum() <= 0) {
            throw new IllegalArgumentException("marketPrice must be positive");
        }
        if (spread == null || side == null) throw new IllegalArgumentException("Spread and side required");
        BigDecimal raw = switch (spread.type()) {
            case PERCENTAGE -> side == OrderSide.BUY ? marketPrice.multiply(ONE.add(spread.value()))
                    : marketPrice.multiply(ONE.subtract(spread.value()));
            case ABSOLUTE -> side == OrderSide.BUY ? marketPrice.add(spread.value()) : marketPrice.subtract(spread.value());
        };
        if (raw.signum() <= 0) throw new IllegalArgumentException("CLIENT_PRICE_NON_POSITIVE");
        return raw;
    }

    public static BigDecimal clientPrice(BigDecimal marketPrice, BigDecimal spread, OrderSide side, int scale) {
        return applySpreadRounded(marketPrice,new SpreadValue(SpreadType.PERCENTAGE,spread,"OZ"),side,scale);
    }

    /** @param marketPrice prix fournisseur @param spread valeur typée @param side sens @param scale précision publiée
     * @return prix arrondi selon la convention historique SAAMP @throws IllegalArgumentException si le résultat est nul */
    public static BigDecimal applySpreadRounded(BigDecimal marketPrice, SpreadValue spread, OrderSide side, int scale) {
        BigDecimal raw = applySpread(marketPrice, spread, side);
        // Favourable-to-SAAMP rounding: BUY rounds up, SELL rounds down.
        BigDecimal published=raw.setScale(scale, side == OrderSide.BUY ? RoundingMode.CEILING : RoundingMode.FLOOR);
        if (published.signum()<=0) throw new IllegalArgumentException("CLIENT_PRICE_NON_POSITIVE");
        return published;
    }

    public static BigDecimal driftRatio(BigDecimal displayedClientPrice, BigDecimal freshClientPrice) {
        if (displayedClientPrice == null || displayedClientPrice.signum() <= 0 || freshClientPrice == null) {
            throw new IllegalArgumentException("prices are required and displayed price must be positive");
        }
        return freshClientPrice.subtract(displayedClientPrice).abs()
                .divide(displayedClientPrice, 12, RoundingMode.HALF_UP);
    }
}
