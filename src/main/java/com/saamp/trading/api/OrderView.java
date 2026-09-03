package com.saamp.trading.api;

import com.saamp.trading.domain.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Client-safe order representation. Provider price, spread internals and SAAMP revenue are not exposed. */
public record OrderView(long id, Asset asset, String pair, OrderSide side, OrderType orderType,
                        BigDecimal requestedQuantity, QuantityUnit requestedUnit, BigDecimal quantityOz,
                        OrderStatus status, BigDecimal indicativeClientPrice, BigDecimal clientPrice,
                        BigDecimal grossAmount, String idempotencyKey, OffsetDateTime createdAt,
                        OffsetDateTime submittedAt, OffsetDateTime executedAt) {
    public static OrderView from(com.saamp.trading.order.TradingOrder o) {
        return new OrderView(o.id(), o.asset(), o.pair(), o.side(), o.orderType(), o.requestedQuantity(), o.requestedUnit(),
                o.quantityOz(), o.status(), o.indicativeClientPrice(), o.clientPrice(), o.grossAmount(), o.idempotencyKey(),
                o.createdAt(), o.submittedAt(), o.executedAt());
    }
}
