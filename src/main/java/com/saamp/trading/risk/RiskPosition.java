package com.saamp.trading.risk;

import java.math.BigDecimal;

/** One signed position used by the StoneX-compatible risk formula. */
public record RiskPosition(BigDecimal signedQuantity, BigDecimal marketPrice, BigDecimal marginRate) {
    public RiskPosition {
        if (signedQuantity == null || marketPrice == null || marginRate == null) {
            throw new IllegalArgumentException("Risk position values are required");
        }
    }
}
