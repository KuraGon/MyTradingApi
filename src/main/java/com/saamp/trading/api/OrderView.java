package com.saamp.trading.api;

import com.saamp.trading.domain.*;
import java.math.BigDecimal;
import java.time.Instant;

/** Représentation client d'un ordre, sans prix fournisseur, spread interne ni revenu SAAMP. */
public record OrderView(long id, Asset asset, String pair, OrderSide side, OrderType orderType,
                        BigDecimal requestedQuantity, QuantityUnit requestedUnit, BigDecimal quantityOz,
                        OrderStatus status, BigDecimal indicativeClientPrice, BigDecimal clientPrice,
                        BigDecimal grossAmount, Instant createdAt,
                        Instant submittedAt, Instant executedAt) {

    /**
     * Sélectionne uniquement les données d'ordre utiles au client.
     *
     * @param order ordre métier appartenant au compte courant
     * @return représentation dépourvue de données StoneX internes
     */
    public static OrderView from(com.saamp.trading.order.TradingOrder order) {
        return new OrderView(order.id(), order.asset(), order.pair(), order.side(), order.orderType(),
                order.requestedQuantity(), order.requestedUnit(), order.quantityOz(), order.status(),
                order.indicativeClientPrice(), order.clientPrice(), order.grossAmount(),
                toInstant(order.createdAt()), toInstant(order.submittedAt()), toInstant(order.executedAt()));
    }

    private static Instant toInstant(java.time.OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
