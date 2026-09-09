package com.saamp.trading.risk;

import com.saamp.trading.domain.OrderSide;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Projection pure : seule la fermeture attribuée à cet ordre peut réduire son risque. */
public final class OrderRiskProjection {
    private OrderRiskProjection() { }

    /**
     * Publie l'avant/après sans créditer les fermetures attribuées aux autres ordres.
     * @param position position comptable actuelle
     * @param quantity quantité de l'ordre
     * @param closeReserved fermetures déjà réservées par les autres ordres de même sens
     * @param side sens du nouvel ordre
     * @param buy prix de liquidation client d'un short
     * @param sell prix de liquidation client d'un long
     * @param execution prix conservateur dans l'enveloppe du preview
     * @param marginRate taux configuré, sans valeur de repli
     * @return fermeture exclusive, ouverture et variation de Free Equity
     */
    public static Result calculate(BigDecimal position, BigDecimal quantity, BigDecimal closeReserved,
                                   OrderSide side, BigDecimal buy, BigDecimal sell,
                                   BigDecimal execution, BigDecimal marginRate) {
        BigDecimal closable=(side==OrderSide.SELL?position:position.negate()).max(BigDecimal.ZERO);
        BigDecimal close=quantity.min(closable.subtract(closeReserved).max(BigDecimal.ZERO));
        BigDecimal open=quantity.subtract(close);
        BigDecimal remaining=position.add(side==OrderSide.BUY?close:close.negate());
        BigDecimal opening=side==OrderSide.BUY?open:open.negate();
        BigDecimal beforeValue=value(position,buy,sell);
        BigDecimal beforeMargin=money(beforeValue.abs().multiply(marginRate));
        BigDecimal afterValue;
        BigDecimal afterMargin;
        if (remaining.signum()==0 || opening.signum()==0 || remaining.signum()==opening.signum()) {
            // Comme PositionService : publier la position entière avant de calculer sa marge.
            afterValue=value(remaining.add(opening),buy,sell);
            afterMargin=money(afterValue.abs().multiply(marginRate));
        } else {
            // La fermeture d'autrui n'est pas acquise : conserver les deux expositions sans compensation.
            BigDecimal remainingValue=value(remaining,buy,sell);
            BigDecimal openingValue=value(opening,buy,sell);
            afterValue=remainingValue.add(openingValue);
            afterMargin=money(remainingValue.abs().multiply(marginRate))
                    .add(money(openingValue.abs().multiply(marginRate)));
        }
        BigDecimal cash=money(quantity.multiply(execution));
        if (side==OrderSide.BUY) cash=cash.negate();
        BigDecimal equityDelta=cash.add(afterValue.subtract(beforeValue));
        BigDecimal marginDelta=afterMargin.subtract(beforeMargin);
        BigDecimal freeDelta=equityDelta.subtract(marginDelta);
        return new Result(close,open,position.add(side==OrderSide.BUY?quantity:quantity.negate()),
                cash,equityDelta,marginDelta,freeDelta,freeDelta.negate().max(BigDecimal.ZERO));
    }

    private static BigDecimal value(BigDecimal quantity, BigDecimal buy, BigDecimal sell) {
        return money(quantity.multiply(quantity.signum()<0?buy:sell));
    }
    private static BigDecimal money(BigDecimal value) { return value.setScale(2,RoundingMode.HALF_UP); }

    /**
     * Résultat monétaire publié avant réutilisation dans l'admission.
     * @param closeQty portion fermée exclusivement
     * @param openQty nouvelle exposition
     * @param projectedPosition position comptable après cet ordre
     * @param cashDelta mouvement devise
     * @param netEquityDelta variation d'équité
     * @param marginDelta variation de marge
     * @param freeEquityDelta variation de capacité
     * @param riskRequired consommation non négative
     */
    public record Result(BigDecimal closeQty, BigDecimal openQty, BigDecimal projectedPosition,
                         BigDecimal cashDelta, BigDecimal netEquityDelta, BigDecimal marginDelta,
                         BigDecimal freeEquityDelta, BigDecimal riskRequired) { }
}
