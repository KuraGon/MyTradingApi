package com.saamp.trading.pricing;

import java.math.BigDecimal;

/** Valeur typée, indépendante de la devise en configuration V1 ; aucune conversion FX. */
public record SpreadValue(SpreadType type, BigDecimal value, String priceUnit) {
    /** @param type sémantique explicite @param value ratio ou montant par oz @param priceUnit OZ uniquement
     * @throws IllegalArgumentException si la configuration est inexploitable */
    public SpreadValue {
        if (type == null || value == null || value.signum() < 0 || !"OZ".equals(priceUnit)
                || type == SpreadType.PERCENTAGE && value.compareTo(BigDecimal.ONE) >= 0)
            throw new IllegalArgumentException("INVALID_SPREAD");
    }
}
