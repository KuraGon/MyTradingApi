package com.saamp.trading.risk.monitor;

import com.saamp.trading.risk.*;
import com.saamp.trading.account.AccountPosition;
import com.saamp.trading.domain.Asset;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;
import static com.saamp.trading.risk.monitor.RiskMonitorPolicy.*;

class RiskMonitorPolicyTest {
    static BigDecimal b(String s) { return new BigDecimal(s); }
    static RiskMonitorProperties config() {
        return new RiskMonitorProperties(Duration.ofSeconds(1),Duration.ofMinutes(1),b("0.2"),Duration.ofSeconds(10),
                Duration.ofSeconds(5),10,Duration.ofSeconds(30),Duration.ofSeconds(1),Duration.ofSeconds(4),
                new RiskMonitorProperties.Smtp("localhost",2525,null,null,"monitor@example.invalid",List.of("recipient@example.invalid"),false,false,Duration.ofSeconds(1)));
    }
    static RiskCalculator.Evaluation calculation(String coverage) {
        var p=new AccountPosition(Asset.XAU,b("-100"),b("100"),b("-10000"),Instant.EPOCH,b("5"),b("500"));
        return RiskCalculator.evaluateAccountPositions(b("10000").add(b(coverage).subtract(b("100")).multiply(b("100"))),List.of(p));
    }
    @ParameterizedTest @CsvSource({"106,NORMAL","105,WARNING","104.5,WARNING","104,CRITICAL","103,CRITICAL","102,LIQUIDATION_REQUIRED","101,LIQUIDATION_REQUIRED","105.0001,NORMAL","104.9999,WARNING","104.0001,WARNING","103.9999,CRITICAL","102.0001,CRITICAL","101.9999,LIQUIDATION_REQUIRED"})
    void exactThresholdsUsePreDisplayCoverage(String coverage,Level expected) {
        var result=calculation(coverage);
        assertThat(classify(result,config())).isEqualTo(expected);
        assertThat(result.coverageBeforeDisplay()).isEqualByComparingTo(coverage);
    }
    @Test void noPositionPrecedesSentinel() {
        var result=RiskCalculator.evaluateAccountPositions(b("20"),List.of());
        assertThat(result.published().coveragePct()).isEqualByComparingTo("999.99");
        assertThat(result.coverageBeforeDisplay()).isNull();
        assertThat(classify(result,config())).isEqualTo(Level.NO_POSITION);
    }
    @Test void longAndShortUseGrossAbsoluteSum() {
        var positions=List.of(new AccountPosition(Asset.XAU,b("10"),b("1000"),b("10000"),Instant.EPOCH,b("5"),b("500")),
                new AccountPosition(Asset.XAG,b("-80"),b("100"),b("-8000"),Instant.EPOCH,b("5"),b("400")));
        var result=RiskCalculator.evaluateAccountPositions(b("1000"),positions).published();
        assertThat(result.grossPosition()).isEqualByComparingTo("18000");
        assertThat(result.positionValuation()).isEqualByComparingTo("2000");
        assertThat(result.netEquity()).isEqualByComparingTo("3000");
        assertThat(result.marginRequirement()).isEqualByComparingTo("900");
        assertThat(result.freeEquity()).isEqualByComparingTo("2100");
        assertThat(result.coveragePct()).isEqualByComparingTo("116.67");
    }
    @Test void sequenceDoesNotSpamAndDirectJumpMakesOneDecision() {
        Instant time=Instant.parse("2026-01-01T00:00:00Z");
        Gates gates=Gates.empty(); Level previous=Level.NORMAL;
        String[] inputs={"104.8","104.3","103.9","101.9","101.5"};
        boolean[] expected={true,false,true,true,false};
        for(int i=0;i<inputs.length;i++) {
            var c=calculation(inputs[i]);
            var d=advance(previous,gates,time,classify(c,config()),c.coverageBeforeDisplay(),time.plusSeconds(1),config());
            assertThat(d.notifyEmail()).isEqualTo(expected[i]); gates=d.gates();previous=d.level();time=time.plusSeconds(1);
        }
        var d=advance(Level.NORMAL,Gates.empty(),time,Level.LIQUIDATION_REQUIRED,b("101.5"),time.plusSeconds(1),config());
        assertThat(d.notifyEmail()).isTrue();assertThat(d.gates().mask()).isEqualTo(7);
    }
    @Test void recoveryNeedsHysteresisDurationAndContinuity() {
        Instant time=Instant.EPOCH;
        Gates gates=advance(Level.NORMAL,Gates.empty(),time,Level.WARNING,b("105"),time,config()).gates();
        for(int i=1;i<=12;i++) {
            var d=advance(Level.WARNING,gates,time.plusSeconds(i-1),Level.NORMAL,b("105.2"),time.plusSeconds(i),config());
            assertThat(d.notifyEmail()).isFalse();gates=d.gates();assertThat(gates.mask()).isEqualTo(1);
        }
        for(int i=13;i<=23;i++) gates=advance(Level.NORMAL,gates,time.plusSeconds(i-1),Level.NORMAL,b("105.3"),time.plusSeconds(i),config()).gates();
        assertThat(gates.mask()).isZero();
        assertThat(advance(Level.NORMAL,gates,time.plusSeconds(23),Level.WARNING,b("104.8"),time.plusSeconds(24),config()).notifyEmail()).isTrue();
        var gap=advance(Level.NORMAL,new Gates(1,Arrays.asList(time,null,null)),time,Level.NORMAL,b("106"),time.plusSeconds(20),config());
        assertThat(gap.gates().mask()).isEqualTo(1);
    }
    @Test void staleAlertsOncePreservesFinancialEpisodeAndRearmsAfterValidPrices() {
        Instant t=Instant.EPOCH; Gates financial=new Gates(7,Arrays.asList(t,t,t));
        var enter=advance(Level.LIQUIDATION_REQUIRED,financial,t,Level.PRICE_STALE,null,t.plusSeconds(1),config());
        assertThat(enter.notifyEmail()).isTrue();assertThat(enter.gates().mask()).isEqualTo(7);
        var repeat=advance(enter.level(),enter.gates(),t,Level.PRICE_STALE,null,t.plusSeconds(2),config());
        assertThat(repeat.notifyEmail()).isFalse();
        var recover=advance(repeat.level(),repeat.gates(),t,Level.LIQUIDATION_REQUIRED,b("101"),t.plusSeconds(3),config());
        assertThat(recover.notifyEmail()).isFalse();
        assertThat(advance(recover.level(),recover.gates(),t,Level.PRICE_STALE,null,t.plusSeconds(4),config()).notifyEmail()).isTrue();
        var close=advance(recover.level(),recover.gates(),t,Level.NO_POSITION,null,t.plusSeconds(5),config());
        assertThat(close.notifyEmail()).isFalse();assertThat(close.gates().mask()).isZero();
    }
    @Test void parametersAreRequiredAndNoImplicitHysteresisExists() {
        assertThatThrownBy(()->new RiskMonitorProperties(null,null,null,null,null,0,null,null,null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(config().smtp().toString()).doesNotContain("recipient");
    }
    @Test void customizedThresholdsDriveBothClassificationAndRearm() {
        var c=config();
        var custom=new RiskMonitorProperties(c.interval(),c.maxPriceAge(),c.hysteresisPoints(),c.rearmDuration(),c.maxObservationGap(),c.batchSize(),
                c.claimLease(),c.retryInitial(),c.retryMax(),c.smtp(),b("110"),b("108"),b("106"),5);
        assertThat(classify(calculation("110.0001"),custom)).isEqualTo(Level.NORMAL);
        assertThat(classify(calculation("110"),custom)).isEqualTo(Level.WARNING);
        assertThat(classify(calculation("108.0001"),custom)).isEqualTo(Level.WARNING);
        assertThat(classify(calculation("108"),custom)).isEqualTo(Level.CRITICAL);
        assertThat(classify(calculation("106.0001"),custom)).isEqualTo(Level.CRITICAL);
        assertThat(classify(calculation("106"),custom)).isEqualTo(Level.LIQUIDATION_REQUIRED);
        var t=Instant.EPOCH;var gates=new Gates(1,Arrays.asList(null,null,null));
        for(int i=0;i<12;i++) gates=advance(Level.NORMAL,gates,t.plusSeconds(i-1),Level.NORMAL,b("110.2"),t.plusSeconds(i),custom).gates();
        assertThat(gates.mask()).isEqualTo(1);
        for(int i=12;i<=22;i++) gates=advance(Level.NORMAL,gates,t.plusSeconds(i-1),Level.NORMAL,b("110.3"),t.plusSeconds(i),custom).gates();
        assertThat(gates.mask()).isZero();
        assertThat(advance(Level.NORMAL,gates,t.plusSeconds(22),Level.WARNING,b("109"),t.plusSeconds(23),custom).notifyEmail()).isTrue();
    }
}
