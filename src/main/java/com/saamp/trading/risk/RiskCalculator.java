package com.saamp.trading.risk;

import com.saamp.trading.domain.RiskStatus;
import com.saamp.trading.account.AccountPosition;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/**
 * Calcule les indicateurs communs aux relevés de référence et aux positions client publiées.
 * La couverture conserve sa formule validée : 100 + netEquity / grossPosition * 100.
 * Elle ne représente jamais un taux de marge par métal.
 */
public final class RiskCalculator {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal WARNING = new BigDecimal("105");
    private static final BigDecimal CRITICAL = new BigDecimal("102");

    private RiskCalculator() {
    }

    /**
     * Préserve le calcul des agrégats bruts du relevé fournisseur de référence.
     * @param totalFunds fonds exprimés dans la devise du relevé
     * @param positions positions signées du relevé
     * @return indicateurs arrondis à leur précision de publication
     * @throws IllegalArgumentException si les fonds ou les positions sont absents
     */
    public static RiskResult calculate(BigDecimal totalFunds, Collection<RiskPosition> positions) {
        if (totalFunds == null || positions == null) {
            throw new IllegalArgumentException("totalFunds and positions are required");
        }

        BigDecimal positionValuation = BigDecimal.ZERO;
        BigDecimal marginRequirement = BigDecimal.ZERO;
        BigDecimal grossPosition = BigDecimal.ZERO;

        for (RiskPosition position : positions) {
            BigDecimal valuation = position.signedQuantity().multiply(position.marketPrice());
            positionValuation = positionValuation.add(valuation);
            BigDecimal absoluteValuation = valuation.abs();
            grossPosition = grossPosition.add(absoluteValuation);
            marginRequirement = marginRequirement.add(absoluteValuation.multiply(position.marginRate()));
        }

        return summarize(totalFunds, positionValuation, marginRequirement, grossPosition).published();
    }

    /**
     * Additionne les lignes déjà publiées pour que le dashboard reste cohérent au centime.
     * @param totalFunds solde devise du compte, hors positions métal
     * @param positions liquidations client et marges calculées une seule fois par métal
     * @return synthèse dont les totaux correspondent exactement aux lignes affichées
     * @throws IllegalArgumentException si les fonds ou les positions sont absents
     */
    public static RiskResult calculateAccountPositions(BigDecimal totalFunds, Collection<AccountPosition> positions) {
        return evaluateAccountPositions(totalFunds, positions).published();
    }

    /**
     * Expose la précision de décision sans reproduire la formule ni changer les DTO publiés.
     * @param totalFunds fonds du compte
     * @param positions lignes client déjà arrondies
     * @return calcul unique et sa publication
     * @throws IllegalArgumentException si les entrées sont absentes
     */
    public static Evaluation evaluateAccountPositions(BigDecimal totalFunds, Collection<AccountPosition> positions) {
        if (totalFunds == null || positions == null) {
            throw new IllegalArgumentException("totalFunds and positions are required");
        }
        BigDecimal valuation = BigDecimal.ZERO;
        BigDecimal margin = BigDecimal.ZERO;
        BigDecimal gross = BigDecimal.ZERO;
        for (AccountPosition position : positions) {
            valuation = valuation.add(position.valuation());
            gross = gross.add(position.valuation().abs());
            margin = margin.add(position.marginRequirement());
        }
        return summarize(totalFunds, valuation, margin, gross);
    }

    private static Evaluation summarize(BigDecimal totalFunds, BigDecimal positionValuation,
                                        BigDecimal marginRequirement, BigDecimal grossPosition) {
        // StoneX publishes and reuses monetary aggregates at 2 decimals.
        // Round each published aggregate before feeding the next calculation so the
        // downstream values reproduce the provider statement to the cent.
        BigDecimal publishedTotalFunds = totalFunds.setScale(2, RoundingMode.HALF_UP);
        BigDecimal publishedPositionValuation = positionValuation.setScale(2, RoundingMode.HALF_UP);
        BigDecimal publishedMarginRequirement = marginRequirement.setScale(2, RoundingMode.HALF_UP);
        BigDecimal netEquity = publishedTotalFunds.add(publishedPositionValuation).setScale(2, RoundingMode.HALF_UP);
        BigDecimal freeEquity = netEquity.subtract(publishedMarginRequirement).setScale(2, RoundingMode.HALF_UP);

        BigDecimal publishedGrossPosition = grossPosition.setScale(2, RoundingMode.HALF_UP);
        BigDecimal coveragePct;
        RiskStatus status;
        if (publishedGrossPosition.signum() == 0) {
            coveragePct = new BigDecimal("999.99");
            status = RiskStatus.NO_POSITION;
        } else {
            // Indicateur existant : 100 + netEquity / grossPosition * 100.
            // Ce pourcentage de couverture ne représente pas le taux de marge par métal.
            coveragePct = ONE_HUNDRED.add(
                    netEquity.divide(publishedGrossPosition, 12, RoundingMode.HALF_UP).multiply(ONE_HUNDRED));
            if (coveragePct.compareTo(CRITICAL) <= 0) {
                status = RiskStatus.CRITICAL;
            } else if (coveragePct.compareTo(WARNING) < 0) {
                status = RiskStatus.WARNING;
            } else {
                status = RiskStatus.NORMAL;
            }
        }

        return new Evaluation(new RiskResult(
                publishedTotalFunds,
                publishedPositionValuation,
                netEquity,
                publishedMarginRequirement,
                freeEquity,
                publishedGrossPosition,
                coveragePct.setScale(2, RoundingMode.HALF_UP),
                status), publishedGrossPosition.signum() == 0 ? null : coveragePct);
    }

    /** Résultat interne : le pourcentage de décision est absent lorsque l'exposition brute est nulle.
     * @param published résultat historique inchangé
     * @param coverageBeforeDisplay couverture avant arrondi d'affichage
     */
    public record Evaluation(RiskResult published, BigDecimal coverageBeforeDisplay) { }
}
