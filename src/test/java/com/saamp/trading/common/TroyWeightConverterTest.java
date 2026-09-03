package com.saamp.trading.common;

import com.saamp.trading.domain.QuantityUnit;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class TroyWeightConverterTest {
    @Test void oneTroyOunceInGramsReturnsOneOunce() {
        assertThat(TroyWeightConverter.toTroyOunces(new BigDecimal("31.1034768"), QuantityUnit.G))
                .isEqualByComparingTo("1.000000");
    }
}
