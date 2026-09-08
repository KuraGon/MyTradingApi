package com.saamp.trading.as400;

import com.saamp.trading.domain.Asset;
import com.saamp.trading.domain.OrderSide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import static com.saamp.trading.as400.As400SyncFixtures.*;
import static org.assertj.core.api.Assertions.*;

class SicouviMovementTest {
    @ParameterizedTest
    @CsvSource({"BUY,EUR,V,A,15267","SELL,EUR,A,V,15267","BUY,USD,V,A,15268","SELL,USD,A,V,15268"})
    void mapsFourAccountingLegs(OrderSide side,String currency,String client,String opposite,int stonex) {
        var fx=new BigDecimal(currency.equals("EUR")?"1.00":"1.17");
        var legs=execution(side,currency,"EXID-123").build(event(As400SyncState.PENDING),MAPPING,fx,List.of(1,2,3,4));
        assertThat(legs).hasSize(4);
        assertThat(legs).extracting(SicouviMovement::ste).containsExactly("B","B","I","I");
        assertThat(legs).extracting(SicouviMovement::nucli).containsExactly(123456,15001,15002,stonex);
        assertThat(legs).extracting(SicouviMovement::side).containsExactly(client,opposite,client,opposite);
        assertThat(legs).extracting(SicouviMovement::sicoui).containsExactly(1,2,3,4);
        assertThat(legs).allSatisfy(m->{
            assertThat(m.fx()).isEqualTo(fx);
            assertThat(m.ref3()).isEqualTo("EXID-123");
            assertThat(m.grams()).isEqualTo(new BigDecimal("31.10"));
            assertThat(m.quotation()).isEqualTo((m.nucli()==123456?new BigDecimal("57.41702408"):new BigDecimal("56"))
                    .multiply(new BigDecimal("1000")).divide(fx.multiply(new BigDecimal("31.10348")),0,RoundingMode.HALF_UP).setScale(4));
        });
    }

    @ParameterizedTest
    @CsvSource({"XAU,O,OR SPOT,OR SPOT","XAG,A,ARGENT SPOT,ARGENT SPO","XPT,P,PT SPOT,PT SPOT","XPD,D,PD SPOT,PD SPOT"})
    void mapsMetalAndBoundedReferences(Asset metal,String code,String condition,String ref2) {
        var movement=map(metal,"1","57.41702408");
        assertThat(movement.metal()).isEqualTo(code);
        assertThat(movement.condition()).isEqualTo(condition).hasSizeLessThanOrEqualTo(15);
        assertThat(movement.ref2()).isEqualTo(ref2).hasSizeLessThanOrEqualTo(10);
    }

    @ParameterizedTest
    @CsvSource({"1,31.10","0.000482,0.01","0.000483,0.02","2.123456,66.05"})
    void roundsOfficialOuncesToGrams(String oz,String grams) {
        assertThat(map(Asset.XAU,oz,"57.41702408").grams()).isEqualTo(new BigDecimal(grams));
    }

    @Test void refusesZeroRoundedWeight() {
        assertThatThrownBy(()->map(Asset.XAU,"0.000001","57.41702408")).hasMessage("AS400_WEIGHT_ROUNDS_TO_ZERO");
    }

    @Test void usesExactLegacyFormulaAndPublishedClientPrice() {
        var movement=map(Asset.XAU,"1","57.41702408");
        assertThat(movement.fx()).isEqualTo(new BigDecimal("1.00"));
        assertThat(movement.quotation()).isEqualTo(new BigDecimal("1846.0000"));
        assertThat(map(Asset.XAU,"1","57.43257582").quotation()).isEqualTo(new BigDecimal("1847.0000"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings={" ","EXID-12345678901234567"})
    void refusesMissingOrOverlongExid(String exid) {
        assertThatThrownBy(()->execution(OrderSide.BUY,"EUR",exid).build(event(As400SyncState.PENDING),
                MAPPING,new BigDecimal("1.00"),List.of(1,2,3,4)))
                .hasMessage(exid!=null && exid.length()>20?"AS400_EXID_TOO_LONG":"AS400_EXID_MISSING");
    }

    @Test void doesNotTruncateTwentyCharacterExid() {
        var exid="12345678901234567890";
        assertThat(execution(OrderSide.BUY,"EUR",exid).build(event(As400SyncState.PENDING),
                MAPPING,new BigDecimal("1.00"),List.of(1,2,3,4))).allSatisfy(m -> assertThat(m.ref3()).isEqualTo(exid));
    }

    @Test void usesExecutionTimeInParisIncludingDateRolloverAndWinter() {
        var movement=movement(event(As400SyncState.PENDING));
        assertThat(movement.executionDate()).isEqualTo(260707);
        assertThat(movement.executionTime()).isEqualTo(1542);
        assertThat(SicouviMovement.time(Instant.parse("2026-01-06T22:15:42Z"))).isEqualTo(231542);
    }

    @Test void missingTradingNucliDoesNotFallbackToCommercial() {
        var e=event(As400SyncState.PENDING);
        var missing=new As400SyncEvent(e.id(),e.orderId(),e.target(),0,e.claimToken(),e.state(),"B",null);
        assertThatThrownBy(()->movement(missing)).hasMessage("AS400_TRADING_ACCOUNT_MAPPING_MISSING");
    }

    @ParameterizedTest @ValueSource(ints={0,1000000})
    void refusesOutOfRangeSicoui(int sicoui) {
        assertThatThrownBy(()->SicouviMovement.from("B",sicoui,123,Asset.XAU,"V",BigDecimal.ONE,
                BigDecimal.TEN,BigDecimal.ONE,EXECUTED,"EXID")).hasMessage("AS400_SICOUI_INVALID");
    }

    @Test void rejectsUnsupportedMetalAndDecimalOverflow() {
        assertThatThrownBy(()->map(Asset.EUR,"1","100")).hasMessage("AS400_METAL_UNSUPPORTED");
        assertThatThrownBy(()->map(Asset.XAU,"999999999","100")).hasMessage("AS400_WEIGHT_OVERFLOW");
        assertThatThrownBy(()->map(Asset.XAU,"1","999999999")).hasMessage("AS400_QUOTATION_INVALID");
    }

    @Test void rejectsNonLfmpClientWithoutOverridingCompany() {
        var e=event(As400SyncState.PENDING);
        var wrong=new As400SyncEvent(e.id(),e.orderId(),e.target(),0,e.claimToken(),e.state(),"I",123456);
        assertThatThrownBy(()->execution(OrderSide.BUY,"EUR","EXID").build(wrong,MAPPING,BigDecimal.ONE,List.of(1,2,3,4)))
                .hasMessage("AS400_CLIENT_COMPANY_NOT_LFMP");
    }

    private SicouviMovement map(Asset asset,String oz,String price) {
        return SicouviMovement.from("B",1,123456,asset,"V",new BigDecimal(oz),new BigDecimal(price),
                new BigDecimal("1.00"),EXECUTED,"EXID-123");
    }
}
