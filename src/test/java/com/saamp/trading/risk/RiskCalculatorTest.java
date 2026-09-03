package com.saamp.trading.risk;

import com.saamp.trading.domain.RiskStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RiskCalculatorTest {

    @Test
    void reproducesStoneXReferenceStatementFrom13August2026() {
        var positions = List.of(
                p("-2282.548", "4384.47", "0.05"),
                p("-25118.296", "64.86075", "0.07"),
                p("-9267.877", "1733.27", "0.12"),
                p("-378.020", "1342.42", "0.12"),
                p("26243811.010", "1.15362", "0.03"));

        RiskResult result = RiskCalculator.calculate(new BigDecimal("2348919.97"), positions);

        assertThat(result.positionValuation()).isEqualByComparingTo("2067235.73");
        assertThat(result.netEquity()).isEqualByComparingTo("4416155.70");
        assertThat(result.marginRequirement()).isEqualByComparingTo("3511236.50");
        assertThat(result.freeEquity()).isEqualByComparingTo("904919.20");
        assertThat(result.grossPosition()).isEqualByComparingTo("58483534.78");
        assertThat(result.coveragePct()).isEqualByComparingTo("107.55");
        assertThat(result.status()).isEqualTo(RiskStatus.NORMAL);
    }

    private static RiskPosition p(String q, String price, String margin) {
        return new RiskPosition(new BigDecimal(q), new BigDecimal(price), new BigDecimal(margin));
    }
}
