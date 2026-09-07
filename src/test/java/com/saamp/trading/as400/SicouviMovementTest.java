package com.saamp.trading.as400;

import com.saamp.trading.domain.Asset;
import com.saamp.trading.domain.OrderSide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.time.Instant;
import static com.saamp.trading.as400.As400SyncFixtures.*;
import static org.assertj.core.api.Assertions.*;

class SicouviMovementTest {
    @ParameterizedTest
    @CsvSource({"BUY,V","SELL,A"})
    void reversesClientSide(OrderSide side,String expected) {
        assertThat(map(Asset.XAU,side,"1","57.41702408","EUR").side()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"XAU,O,OR SPOT,OR SPOT","XAG,A,ARGENT SPOT,ARGENT SPO","XPT,P,PT SPOT,PT SPOT","XPD,D,PD SPOT,PD SPOT"})
    void mapsMetalAndBoundedReferences(Asset metal,String code,String condition,String ref2) {
        var movement=map(metal,OrderSide.BUY,"1","57.41702408","EUR");
        assertThat(movement.metal()).isEqualTo(code);
        assertThat(movement.condition()).isEqualTo(condition).hasSizeLessThanOrEqualTo(15);
        assertThat(movement.ref2()).isEqualTo(ref2).hasSizeLessThanOrEqualTo(10);
    }

    @ParameterizedTest
    @CsvSource({"1,31.10","0.000482,0.01","0.000483,0.02","2.123456,66.05"})
    void roundsOfficialOuncesToGrams(String oz,String grams) {
        assertThat(map(Asset.XAU,OrderSide.BUY,oz,"57.41702408","EUR").grams()).isEqualTo(new BigDecimal(grams));
    }

    @Test
    void refusesZeroRoundedWeight() {
        assertThatThrownBy(()->map(Asset.XAU,OrderSide.BUY,"0.000001","57.41702408","EUR"))
                .isInstanceOf(As400SyncDataException.class).hasMessage("AS400_WEIGHT_ROUNDS_TO_ZERO");
    }

    @Test
    void usesExactLegacyFormulaAndPublishedClientPrice() {
        var movement=map(Asset.XAU,OrderSide.BUY,"1","57.41702408","EUR");
        assertThat(movement.fx()).isEqualTo(new BigDecimal("1.00000"));
        assertThat(movement.quotation()).isEqualTo(new BigDecimal("1846.0000"));
        assertThat(map(Asset.XAU,OrderSide.BUY,"1","57.43257582","EUR").quotation())
                .isEqualTo(new BigDecimal("1847.0000"));
    }

    @Test
    void refusesUsdWithoutOfficialFx() {
        assertThatThrownBy(()->map(Asset.XAU,OrderSide.BUY,"1","100","USD"))
                .hasMessage("AS400_FX_RATE_UNAVAILABLE");
    }

    @Test
    void referenceIsStableUniqueAndFitsBigint() {
        assertThat(SicouviMovement.reference(12345)).isEqualTo("MT-9IX");
        assertThat(SicouviMovement.reference(Long.MAX_VALUE)).hasSizeLessThanOrEqualTo(20);
        assertThat(SicouviMovement.reference(12345)).isEqualTo(SicouviMovement.reference(12345))
                .isNotEqualTo(SicouviMovement.reference(12346));
    }

    @Test
    void usesExecutionTimeInParisIncludingDateRolloverAndWinter() {
        var movement=movement(event(As400SyncState.PENDING));
        assertThat(movement.executionDate()).isEqualTo(260707);
        assertThat(movement.executionTime()).isEqualTo(1542);
        assertThat(SicouviMovement.time(Instant.parse("2026-01-06T22:15:42Z"))).isEqualTo(231542);
        assertThat(movement.ste()).isEqualTo("i");
        assertThat(movement.nucli()).isEqualTo(123456);
    }

    @Test
    void missingTradingNucliDoesNotFallbackToCommercial() {
        var e=event(As400SyncState.PENDING);
        var missing=new As400SyncEvent(e.id(),e.orderId(),e.target(),0,e.claimToken(),e.state(),e.sicoui(),null,"I",null);
        assertThatThrownBy(()->movement(missing)).hasMessage("AS400_TRADING_ACCOUNT_MAPPING_MISSING");
    }

    @ParameterizedTest
    @CsvSource({"0","1000000"})
    void refusesOutOfRangeSicoui(int sicoui) {
        var e=event(As400SyncState.PENDING);
        var invalid=new As400SyncEvent(e.id(),e.orderId(),e.target(),0,e.claimToken(),e.state(),sicoui,null,"I",1);
        assertThatThrownBy(()->movement(invalid)).hasMessage("AS400_SICOUI_INVALID");
    }

    @Test
    void rejectsUnsupportedMetalAndDecimalOverflow() {
        assertThatThrownBy(()->map(Asset.EUR,OrderSide.BUY,"1","100","EUR")).hasMessage("AS400_METAL_UNSUPPORTED");
        assertThatThrownBy(()->map(Asset.XAU,OrderSide.BUY,"999999999","100","EUR")).hasMessage("AS400_WEIGHT_OVERFLOW");
        assertThatThrownBy(()->map(Asset.XAU,OrderSide.BUY,"1","999999999","EUR")).hasMessage("AS400_QUOTATION_INVALID");
    }

    private SicouviMovement map(Asset asset,OrderSide side,String oz,String price,String currency) {
        return SicouviMovement.from(event(As400SyncState.PENDING),asset,side,new BigDecimal(oz),new BigDecimal(price),currency,EXECUTED);
    }
}
