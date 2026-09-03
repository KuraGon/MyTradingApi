package com.saamp.trading.api;

import com.saamp.trading.domain.RiskStatus;
import com.saamp.trading.risk.RiskResult;

import java.math.BigDecimal;

/** Indicateurs de risque explicitement autorisés dans le contrat client MyTrading. */
public record RiskSummaryView(BigDecimal totalFunds, BigDecimal positionValuation, BigDecimal netEquity,
                              BigDecimal marginRequirement, BigDecimal freeEquity, BigDecimal grossPosition,
                              BigDecimal coveragePct, RiskStatus riskStatus) {

    /**
     * Copie les seuls indicateurs internes validés pour l'affichage client.
     *
     * @param result résultat du calcul de risque métier
     * @return DTO stable destiné à l'API publique
     */
    public static RiskSummaryView from(RiskResult result) {
        return new RiskSummaryView(result.totalFunds(), result.positionValuation(), result.netEquity(),
                result.marginRequirement(), result.freeEquity(), result.grossPosition(), result.coveragePct(),
                result.status());
    }
}
