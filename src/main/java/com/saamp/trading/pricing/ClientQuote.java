package com.saamp.trading.pricing;

import com.saamp.trading.domain.Asset;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ClientQuote(Asset asset, String pair, BigDecimal marketBid, BigDecimal marketAsk,
                          BigDecimal clientBuyPriceRaw, BigDecimal clientSellPriceRaw,
                          BigDecimal clientBuyPrice, BigDecimal clientSellPrice,
                          BigDecimal spreadBuy, BigDecimal spreadSell, int spreadConfigVersion,
                          OffsetDateTime priceAsOf) {}
