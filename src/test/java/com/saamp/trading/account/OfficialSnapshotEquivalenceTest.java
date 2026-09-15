package com.saamp.trading.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.saamp.trading.domain.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

class OfficialSnapshotEquivalenceTest {
    private static BigDecimal d(String value) { return new BigDecimal(value); }
    private static OfficialTradingBalanceReader.Reading reading(String xag, Map<Long, Boolean> posted) {
        var balances = new EnumMap<Asset, BigDecimal>(Asset.class);
        balances.put(Asset.EUR, d("100.00")); balances.put(Asset.XAU, d("0"));
        balances.put(Asset.XAG, d(xag)); balances.put(Asset.XPT, d("0")); balances.put(Asset.XPD, d("0"));
        return new OfficialTradingBalanceReader.Reading(balances, posted);
    }
    private static PendingTradingAdjustmentRepository.Adjustment adjustment(long id, String qty, String amount) {
        return new PendingTradingAdjustmentRepository.Adjustment(id, Asset.XAG, Asset.EUR, OrderSide.SELL,
                d(qty), d(amount), 10L, 20L, "B", 10002, 30, 40, "EX-1");
    }

    @Test void balancesCompareNumericallyNotByScale() {
        assertThat(OfficialSnapshotEquivalence.businessEquivalent(reading("66.87", Map.of()), reading("66.870000", Map.of()))).isTrue();
        assertThat(OfficialSnapshotEquivalence.businessEquivalent(reading("66.87", Map.of()), reading("66.88", Map.of()))).isFalse();
    }
    @Test void clientPostedMapOrderIsNotBusinessData() {
        var first = new LinkedHashMap<Long, Boolean>(); first.put(2L, true); first.put(1L, false);
        var second = new LinkedHashMap<Long, Boolean>(); second.put(1L, false); second.put(2L, true);
        assertThat(OfficialSnapshotEquivalence.businessEquivalent(reading("1", first), reading("1.0", second))).isTrue();
    }
    @Test void adjustmentsCompareAsAnUnorderedNumericMultiset() {
        var a = adjustment(1, "0.032151", "2.04"); var b = adjustment(2, "0.1", "6.38");
        assertThat(OfficialSnapshotEquivalence.adjustmentsEquivalent(List.of(a, b), List.of(
                adjustment(2, "0.100000", "6.380000"), adjustment(1, "0.0321510", "2.040")))).isTrue();
        assertThat(OfficialSnapshotEquivalence.adjustmentsEquivalent(List.of(a), List.of(b))).isFalse();
    }
}
