package com.saamp.trading.risk;

import com.saamp.trading.domain.RiskStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/**
 * StoneX-compatible risk formulas validated in the MyTrading specification.
 *
 * <p>The calculator deliberately receives {@code totalFunds} separately from positions.
 * This allows the exact StoneX statement fixture to be reproduced while client accounts
 * can treat the base-currency balance as funds and pass metal positions only.</p>
 */
public final class RiskCalculator {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal WARNING = new BigDecimal("105");
    private static final BigDecimal CRITICAL = new BigDecimal("102");

    private RiskCalculator() {
    }

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

        return new RiskResult(
                publishedTotalFunds,
                publishedPositionValuation,
                netEquity,
                publishedMarginRequirement,
                freeEquity,
                publishedGrossPosition,
                coveragePct.setScale(2, RoundingMode.HALF_UP),
                status);
    }
}
