package com.saamp.trading.risk;

import com.saamp.trading.domain.OrderSide;
import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Vérifie les variations de capacité sans base ni fournisseur. */
class OrderRiskProjectionTest {
    @ParameterizedTest
    @CsvSource({
        "10,SELL,4,0,4,0,6,19.20",
        "10,SELL,30,0,10,20,-20,-56.00",
        "-10,SELL,4,0,0,4,-14,-20.80",
        "-10,BUY,4,0,4,0,-6,19.20",
        "-10,BUY,30,0,10,20,20,-56.00",
        "0,BUY,4,0,0,4,4,-20.80",
        "10,SELL,4,10,0,4,6,-20.80",
        "-10,BUY,4,10,0,4,-6,-20.80"
    })
    void attributesOnlyOwnedClose(String position,OrderSide side,String qty,String already,String close,String open,String projected,String delta) {
        var result=OrderRiskProjection.calculate(b(position),b(qty),b(already),side,b("100"),b("100"),
                side==OrderSide.BUY?b("100.2"):b("99.8"),b("0.05"));
        assertThat(result.closeQty()).isEqualByComparingTo(close);
        assertThat(result.openQty()).isEqualByComparingTo(open);
        assertThat(result.projectedPosition()).isEqualByComparingTo(projected);
        assertThat(result.freeEquityDelta()).isEqualByComparingTo(delta);
        assertThat(result.riskRequired()).isEqualByComparingTo(b(delta).negate().max(BigDecimal.ZERO));
    }

    @Test void shortUsesClientBuyAndExactConfiguredMargin() {
        var result=OrderRiskProjection.calculate(BigDecimal.ZERO,b("10"),BigDecimal.ZERO,OrderSide.SELL,
                b("101"),b("99"),b("98.802"),b("0.073"));
        // Equity: 988.02 - 1010 = -21.98 ; marge publiée: 73.73.
        assertThat(result.riskRequired()).isEqualByComparingTo("95.71");
        assertThat(result.marginDelta()).isEqualByComparingTo("73.73");
    }

    @ParameterizedTest
    @CsvSource({
        "10,SELL,4,6,18.60",
        "10,SELL,14,-4,1.10",
        "-10,BUY,4,-6,19.80",
        "-10,BUY,14,4,5.30",
        "-10,SELL,4,-14,-45.40"
    })
    void asymmetricClientSpreadsMatchFinalPositionCashAndMargin(String position,OrderSide side,String quantity,String after,String delta) {
        // Liquidation long 97, short 103 ; exécution conservatrice SELL 96.8 / BUY 103.2.
        var result=OrderRiskProjection.calculate(b(position),b(quantity),BigDecimal.ZERO,side,
                b("103"),b("97"),side==OrderSide.BUY?b("103.2"):b("96.8"),b("0.05"));
        assertThat(result.projectedPosition()).isEqualByComparingTo(after);
        assertThat(result.freeEquityDelta()).isEqualByComparingTo(delta);
    }

    @Test void pendingCloseCannotBeAnticipatedWithAsymmetricSpreads() {
        var result=OrderRiskProjection.calculate(b("10"),b("4"),b("10"),OrderSide.SELL,
                b("103"),b("97"),b("96.8"),b("0.05"));
        assertThat(result.closeQty()).isZero();
        assertThat(result.openQty()).isEqualByComparingTo("4");
        assertThat(result.riskRequired()).isEqualByComparingTo("45.40");
    }

    @Test void partialCloseReusesPublishedWholePositionValues() {
        // Avant : 970.01 et marge 48.50 ; après : 582.00 et marge 29.10 ; cash +387.20.
        var result=OrderRiskProjection.calculate(b("10"),b("4"),BigDecimal.ZERO,OrderSide.SELL,
                b("103"),b("97.0005"),b("96.8"),b("0.05"));
        assertThat(result.freeEquityDelta()).isEqualByComparingTo("18.59");
    }

    @Test void addingToShortReusesPublishedWholePositionValues() {
        // Avant : -1030.00 et marge 51.50 ; après : -1442.01 et marge 72.10 ; cash +387.20.
        var result=OrderRiskProjection.calculate(b("-10"),b("4"),BigDecimal.ZERO,OrderSide.SELL,
                b("103.0004"),b("97"),b("96.8"),b("0.05"));
        assertThat(result.riskRequired()).isEqualByComparingTo("45.41");
    }

    private static BigDecimal b(String value) { return new BigDecimal(value); }
}
