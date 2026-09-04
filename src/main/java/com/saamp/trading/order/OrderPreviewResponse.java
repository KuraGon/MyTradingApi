package com.saamp.trading.order;

import com.saamp.trading.domain.Asset;
import com.saamp.trading.domain.OrderSide;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record OrderPreviewResponse(long orderId, Asset asset, OrderSide side, BigDecimal quantityOz,
                                   String pair, BigDecimal indicativeClientPrice, OffsetDateTime priceAsOf,
                                   OffsetDateTime expiresAt, BigDecimal reservedCash, BigDecimal reservedMetal) {}
