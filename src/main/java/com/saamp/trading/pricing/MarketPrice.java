package com.saamp.trading.pricing;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record MarketPrice(String pair, BigDecimal bid, BigDecimal ask, BigDecimal mid,
                          OffsetDateTime priceAsOf, String source, OffsetDateTime updatedAt) {}
