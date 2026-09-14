package com.saamp.trading.configsync;

import com.saamp.trading.domain.Asset;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Versioned pricing configuration copied from MyPortal; ratios are decimal (0.003 = 0.30%). */
public record SpreadConfigCommand(long companyId, Asset asset, BigDecimal spreadBuy, BigDecimal spreadSell,
                                  int configVersion, OffsetDateTime activeFrom,
                                  com.saamp.trading.pricing.SpreadType spreadType,String priceUnit) {
    /** Ancien transport : ratio PERCENTAGE, jamais montant absolu implicite. */
    public SpreadConfigCommand(long companyId,Asset asset,BigDecimal spreadBuy,BigDecimal spreadSell,int configVersion,OffsetDateTime activeFrom) {
        this(companyId,asset,spreadBuy,spreadSell,configVersion,activeFrom,com.saamp.trading.pricing.SpreadType.PERCENTAGE,"OZ");
    }
}
