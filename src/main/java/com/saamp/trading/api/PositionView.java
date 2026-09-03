package com.saamp.trading.api;

import com.saamp.trading.account.AccountPosition;
import com.saamp.trading.domain.Asset;

import java.math.BigDecimal;
import java.time.Instant;

/** Position métal client valorisée exclusivement avec un prix client ajusté. */
public record PositionView(Asset asset, BigDecimal quantityOz, BigDecimal clientPrice,
                           BigDecimal valuation, Instant priceAsOf) {

    /**
     * Transforme le résultat métier sans y ajouter de prix fournisseur ni de spread.
     *
     * @param position position valorisée du compte courant
     * @return vue sûre destinée au client
     */
    public static PositionView from(AccountPosition position) {
        return new PositionView(position.asset(), position.quantityOz(), position.clientPrice(),
                position.valuation(), position.priceAsOf());
    }
}
