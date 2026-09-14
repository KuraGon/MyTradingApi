package com.saamp.trading.pricing;

import com.saamp.trading.domain.Asset;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record SpreadConfig(long id, long companyId, Asset asset, BigDecimal spreadBuy, BigDecimal spreadSell,
                           int configVersion, OffsetDateTime activeFrom, OffsetDateTime activeTo,
                           SpreadType spreadType, String priceUnit) {
    /** Constructeur historique : les valeurs restent des ratios. */
    public SpreadConfig(long id,long companyId,Asset asset,BigDecimal spreadBuy,BigDecimal spreadSell,
                        int configVersion,OffsetDateTime activeFrom,OffsetDateTime activeTo) {
        this(id,companyId,asset,spreadBuy,spreadSell,configVersion,activeFrom,activeTo,SpreadType.PERCENTAGE,"OZ");
    }
    /** @param side sens client @return valeur typée à appliquer à la paire */
    public SpreadValue value(com.saamp.trading.domain.OrderSide side) {
        return new SpreadValue(spreadType,side==com.saamp.trading.domain.OrderSide.BUY?spreadBuy:spreadSell,priceUnit);
    }
}
