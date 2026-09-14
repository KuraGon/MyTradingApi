package com.saamp.trading.pricing;

import com.saamp.trading.domain.Asset;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ClientQuote(Asset asset, String pair, BigDecimal marketBid, BigDecimal marketAsk,
                          BigDecimal clientBuyPriceRaw, BigDecimal clientSellPriceRaw,
                          BigDecimal clientBuyPrice, BigDecimal clientSellPrice,
                          BigDecimal spreadBuy, BigDecimal spreadSell, int spreadConfigVersion,
                          OffsetDateTime priceAsOf, SpreadType spreadType, String priceUnit) {
    /** Constructeur compatible avec les cotations historiques proportionnelles. */
    public ClientQuote(Asset asset,String pair,BigDecimal marketBid,BigDecimal marketAsk,
                       BigDecimal clientBuyPriceRaw,BigDecimal clientSellPriceRaw,BigDecimal clientBuyPrice,BigDecimal clientSellPrice,
                       BigDecimal spreadBuy,BigDecimal spreadSell,int spreadConfigVersion,OffsetDateTime priceAsOf) {
        this(asset,pair,marketBid,marketAsk,clientBuyPriceRaw,clientSellPriceRaw,clientBuyPrice,clientSellPrice,
                spreadBuy,spreadSell,spreadConfigVersion,priceAsOf,SpreadType.PERCENTAGE,"OZ");
    }
}
