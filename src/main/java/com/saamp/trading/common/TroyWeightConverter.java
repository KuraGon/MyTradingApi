package com.saamp.trading.common;

import com.saamp.trading.domain.QuantityUnit;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Converts user-facing weights to the canonical troy-ounce representation. */
public final class TroyWeightConverter {

    public static final BigDecimal GRAMS_PER_TROY_OUNCE = new BigDecimal("31.1034768");
    private static final int CANONICAL_SCALE = 6;

    private TroyWeightConverter() {
    }

    public static BigDecimal toTroyOunces(BigDecimal quantity, QuantityUnit unit) {
        if (quantity == null || unit == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("Quantity and unit are required and quantity must be positive");
        }
        return switch (unit) {
            case OZ -> quantity.setScale(CANONICAL_SCALE, RoundingMode.HALF_UP);
            case G -> quantity.divide(GRAMS_PER_TROY_OUNCE, CANONICAL_SCALE, RoundingMode.HALF_UP);
            case KG -> quantity.multiply(new BigDecimal("1000"))
                    .divide(GRAMS_PER_TROY_OUNCE, CANONICAL_SCALE, RoundingMode.HALF_UP);
        };
    }
}
