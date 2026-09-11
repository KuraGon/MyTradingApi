package com.saamp.trading.account;

import com.saamp.trading.common.TradingException;
import com.saamp.trading.domain.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class EffectiveBalanceServiceTest {
    BalanceRepository projection=mock(BalanceRepository.class);
    AccountRepository accounts=mock(AccountRepository.class);
    PendingTradingAdjustmentRepository pending=mock(PendingTradingAdjustmentRepository.class);
    OfficialTradingBalanceReader official=mock(OfficialTradingBalanceReader.class);
    EffectiveBalanceProperties config=new EffectiveBalanceProperties();
    EffectiveBalanceService service=new EffectiveBalanceService(projection,accounts,pending,official,config);
    TradingAccount account=new TradingAccount(1,1,Asset.EUR,AccountStatus.ACTIVE,null,null,null,"B",17492,20662,1,OffsetDateTime.now(),OffsetDateTime.now());
    static BigDecimal b(String s) { return new BigDecimal(s); }
    static Map<Asset,BigDecimal> amounts(String eur,String xag) {
        var m=new EnumMap<Asset,BigDecimal>(Asset.class);
        for(var a:List.of(Asset.EUR,Asset.XAU,Asset.XAG,Asset.XPT,Asset.XPD))m.put(a,BigDecimal.ZERO);
        m.put(Asset.EUR,b(eur));m.put(Asset.XAG,b(xag));return m;
    }
    PendingTradingAdjustmentRepository.Adjustment fact(long id,OrderSide side) {
        return new PendingTradingAdjustmentRepository.Adjustment(id,Asset.XAG,Asset.EUR,side,b("0.000322"),b("0.01"),13L,1L,"B",20662,1,905627,"SIM-TTnhKlkhlfxyQZfE");
    }
    BigDecimal value(EffectiveBalanceSnapshot s,Asset a) {
        return s.calculatedEffectiveBalances().stream().filter(v->v.asset()==a).findFirst().orElseThrow().quantity();
    }
    @BeforeEach void setup() {
        config.setMode(EffectiveBalanceProperties.Mode.ENFORCED);
        config.setOverlayCutoverAt(Instant.parse("2020-01-01T00:00:00Z"));
        when(accounts.findById(1)).thenReturn(Optional.of(account));
        when(pending.read(1)).thenReturn(List.of());
        when(official.read(eq(account),anyList())).thenReturn(new OfficialTradingBalanceReader.Reading(amounts("0","0"),Map.of()));
    }
    @Test void defaultIsLegacyAndNeverReadsAs400() {
        assertThat(new EffectiveBalanceProperties().getMaxSnapshotAge()).isEqualTo(Duration.ofSeconds(5));
        config.setMode(new EffectiveBalanceProperties().getMode());
        config.setOverlayCutoverAt(null);
        var local=List.of(new Balance(1,Asset.EUR,b("25"),OffsetDateTime.now()));
        when(projection.findAll(1)).thenReturn(local);
        var s=service.capture(account);
        assertThat(s.calculatedEffectiveBalances()).isEmpty();
        assertThat(s.decisionBalances()).isEqualTo(local);
        assertThat(service.forOperation(s)).isEqualTo(local);
        verifyNoInteractions(official,pending);
    }
    @Test void shadowFailureKeepsLocalDecisionsAndReportsUnavailable() {
        config.setMode(EffectiveBalanceProperties.Mode.SHADOW);
        when(official.read(any(),anyList())).thenThrow(new IllegalStateException("timeout"));
        var local=List.of(new Balance(1,Asset.EUR,b("25"),OffsetDateTime.now()));
        when(projection.findAll(1)).thenReturn(local);
        var s=service.capture(account);assertThat(s.available()).isFalse();assertThat(s.decisionBalances()).isEqualTo(local);
        assertThat(s.calculatedEffectiveBalances()).isEmpty();
    }
    @Test void emptyExistingOfficialAccountIsZero() { assertThat(value(service.capture(account),Asset.EUR)).isZero(); }
    @Test void timeoutNeverFallsBackToPositiveProjection() {
        when(official.read(any(),anyList())).thenThrow(new IllegalStateException("timeout"));
        assertThatThrownBy(()->service.capture(account)).isInstanceOfSatisfying(TradingException.class,e->assertThat(e.getCode()).isEqualTo("OFFICIAL_BALANCE_UNAVAILABLE"));
        verifyNoInteractions(projection);
    }
    @Test void tradingIdentityIsPassedUnchangedDespiteCommercialValue() {
        service.capture(account);
        verify(official,times(2)).read(argThat(a->a.as400NucliTrading()==20662),anyList());
    }
    @Test void usdIsExplicitlyUnsupported() {
        var usd=new TradingAccount(1,1,Asset.USD,AccountStatus.ACTIVE,null,null,null,"B",17492,20662,1,OffsetDateTime.now(),OffsetDateTime.now());
        assertThatThrownBy(()->service.capture(usd)).isInstanceOfSatisfying(TradingException.class,e->assertThat(e.getCode()).isEqualTo("OFFICIAL_CURRENCY_UNSUPPORTED"));
        verifyNoInteractions(official);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void buyUsesPersistedAmountsAndOnlyClientImputation(boolean posted) {
        when(pending.read(1)).thenReturn(List.of(fact(22,OrderSide.BUY)));
        when(official.read(any(),anyList())).thenReturn(new OfficialTradingBalanceReader.Reading(amounts(posted?"99.99":"100","0"),Map.of(22L,posted)));
        var s=service.capture(account);
        assertThat(value(s,Asset.EUR)).isEqualByComparingTo("99.99");
        assertThat(s.pendingAdjustments().getOrDefault(Asset.XAG,BigDecimal.ZERO)).isEqualByComparingTo(posted?"0":"0.000322");
        assertThat(s.clientPosted()).containsEntry(22L,posted);
    }
    @Test void sellAddsCashAndSubtractsMetal() {
        when(pending.read(1)).thenReturn(List.of(fact(22,OrderSide.SELL)));
        when(official.read(any(),anyList())).thenReturn(new OfficialTradingBalanceReader.Reading(amounts("100","1"),Map.of(22L,false)));
        var s=service.capture(account);assertThat(value(s,Asset.EUR)).isEqualByComparingTo("100.01");assertThat(value(s,Asset.XAG)).isEqualByComparingTo("0.999678");
    }
    @Test void official100Plus10ThenOfficial110ClientOIsNever120() {
        var f=new PendingTradingAdjustmentRepository.Adjustment(22,Asset.XAG,Asset.EUR,OrderSide.SELL,b("1"),b("10"),13L,1L,"B",20662,1,905627,"SIM-TTnhKlkhlfxyQZfE");
        when(pending.read(1)).thenReturn(List.of(f));
        when(official.read(any(),anyList())).thenReturn(new OfficialTradingBalanceReader.Reading(amounts("100","1"),Map.of(22L,false)));
        assertThat(value(service.capture(account),Asset.EUR)).isEqualByComparingTo("110");
        when(official.read(any(),anyList())).thenReturn(new OfficialTradingBalanceReader.Reading(amounts("110","0"),Map.of(22L,true)));
        assertThat(value(service.capture(account),Asset.EUR)).isEqualByComparingTo("110");
    }
    @Test void severalClientProvisionalStatesRemainIndependent() {
        when(pending.read(1)).thenReturn(List.of(fact(22,OrderSide.BUY),fact(24,OrderSide.SELL)));
        when(official.read(any(),anyList())).thenReturn(new OfficialTradingBalanceReader.Reading(amounts("100","1"),Map.of(22L,true,24L,false)));
        assertThat(value(service.capture(account),Asset.EUR)).isEqualByComparingTo("100.01");
    }
    @Test void order22ClientOHasZeroOverlayAfterRestart() {
        when(pending.read(1)).thenReturn(List.of(fact(22,OrderSide.BUY)));
        when(official.read(any(),anyList())).thenReturn(new OfficialTradingBalanceReader.Reading(amounts("0","0.000322"),Map.of(22L,true)));
        var restarted=new EffectiveBalanceService(projection,accounts,pending,official,config);
        assertThat(restarted.capture(account).pendingAdjustments()).isEmpty();
    }
    @Test void duplicateClientFactsAreUnavailableNotDoubleCounted() {
        when(pending.read(1)).thenReturn(List.of(fact(22,OrderSide.BUY),fact(22,OrderSide.BUY)));
        assertThatThrownBy(()->service.capture(account)).isInstanceOf(TradingException.class);
    }
    @Test void changingAs400ReadIsRejected() {
        when(official.read(any(),anyList())).thenReturn(new OfficialTradingBalanceReader.Reading(amounts("100","0"),Map.of()),new OfficialTradingBalanceReader.Reading(amounts("90","0"),Map.of()));
        assertThatThrownBy(()->service.capture(account)).isInstanceOf(TradingException.class);
    }
    @Test void concurrentFilledInvalidatesAcquiredSnapshotUnderAccountLock() {
        var snapshot=service.capture(account);
        when(pending.read(1)).thenReturn(List.of(fact(22,OrderSide.BUY)));
        assertThatThrownBy(()->service.validate(account,snapshot)).isInstanceOf(TradingException.class);
    }
    @Test void expiredSnapshotCannotAdmit() {
        var snapshot=service.capture(account);
        snapshot=new EffectiveBalanceSnapshot(snapshot.accountId(),snapshot.ste(),snapshot.nucliTrading(),snapshot.officialBalances(),
                snapshot.pendingAdjustments(),snapshot.calculatedEffectiveBalances(),snapshot.decisionBalances(),Instant.now().minusSeconds(60),snapshot.completedAt(),
                snapshot.available(),snapshot.error(),snapshot.clientPosted(),snapshot.facts(),snapshot.enforced());
        final var expired=snapshot;
        assertThatThrownBy(()->service.validate(account,expired)).isInstanceOf(TradingException.class);
    }
    @Test void multipleMetalsUsePersistedDeltasAndExcludeOnlyPostedClient() {
        var gold=new PendingTradingAdjustmentRepository.Adjustment(23,Asset.XAU,Asset.EUR,OrderSide.SELL,b("0.5"),b("20"),null,null,null,null,null,null,null);
        when(pending.read(1)).thenReturn(List.of(fact(22,OrderSide.BUY),gold));
        when(official.read(any(),anyList())).thenReturn(new OfficialTradingBalanceReader.Reading(amounts("100","0"),Map.of(22L,false,23L,false)));
        var s=service.capture(account);
        assertThat(value(s,Asset.EUR)).isEqualByComparingTo("119.99");
        assertThat(value(s,Asset.XAU)).isEqualByComparingTo("-0.5");
        assertThat(value(s,Asset.XAG)).isEqualByComparingTo("0.000322");
    }
    @Test void shadowComputesEffectiveSnapshotButDoesNotChangeOperationalBalance() {
        config.setMode(EffectiveBalanceProperties.Mode.SHADOW);
        when(pending.read(1)).thenReturn(List.of(fact(22,OrderSide.BUY)));
        when(official.read(any(),anyList())).thenReturn(new OfficialTradingBalanceReader.Reading(amounts("100","0"),Map.of(22L,false)));
        var local=List.of(new Balance(1,Asset.EUR,b("7"),OffsetDateTime.now()));
        when(projection.findAll(1)).thenReturn(local);
        var s=service.capture(account);
        assertThat(s.officialBalances().get(Asset.EUR)).isEqualByComparingTo("100");
        assertThat(s.pendingAdjustments().get(Asset.EUR)).isEqualByComparingTo("-0.01");
        assertThat(value(s,Asset.EUR)).isEqualByComparingTo("99.99");
        assertThat(value(s,Asset.XAG)).isEqualByComparingTo("0.000322");
        assertThat(s.decisionBalances()).isEqualTo(local);
        assertThat(service.forOperation(s)).isEqualTo(local);
        assertThat(service.findAll(1)).isEqualTo(local);
        assertThat(service.validate(account,s)).isEqualTo(local);
    }
    @Test void enforcedDecisionsUseCalculatedEffectiveIncludingPending() {
        when(pending.read(1)).thenReturn(List.of(fact(22,OrderSide.BUY)));
        when(official.read(any(),anyList())).thenReturn(new OfficialTradingBalanceReader.Reading(amounts("100","0"),Map.of(22L,false)));
        var s=service.capture(account);
        assertThat(value(s,Asset.EUR)).isEqualByComparingTo("99.99");
        assertThat(s.decisionBalances()).isEqualTo(s.calculatedEffectiveBalances());
        assertThat(service.forOperation(s)).isEqualTo(s.decisionBalances());
        assertThat(service.validate(account,s)).isEqualTo(s.decisionBalances());
        verifyNoInteractions(projection);
    }
    @Test void shadowRevalidatesLocalDecisionAfterAccountLock() {
        config.setMode(EffectiveBalanceProperties.Mode.SHADOW);
        var before=List.of(new Balance(1,Asset.EUR,b("7"),OffsetDateTime.now()));
        var after=List.of(new Balance(1,Asset.EUR,b("3"),OffsetDateTime.now()));
        when(projection.findAll(1)).thenReturn(before,after);
        var s=service.capture(account);
        assertThat(service.forOperation(s)).isEqualTo(before);
        assertThat(service.validate(account,s)).isEqualTo(after);
    }
    @ParameterizedTest @ValueSource(strings={"SHADOW","ENFORCED"})
    void missingCutoverCannotSilentlyFallBackAtRuntime(String mode) {
        config.setMode(EffectiveBalanceProperties.Mode.valueOf(mode));
        config.setOverlayCutoverAt(null);
        assertThatThrownBy(()->service.capture(account)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("overlay-cutover-at");
        verifyNoInteractions(projection,pending,official);
    }
    @Test void propertiesBindExplicitModeAndAge() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner().withUserConfiguration(Binding.class)
            .withPropertyValues("trading.effective-balance.mode=ENFORCED","trading.effective-balance.max-snapshot-age=2s",
                "trading.effective-balance.overlay-cutover-at=2020-01-01T00:00:00Z")
            .run(c->{assertThat(c).hasNotFailed();var p=c.getBean(EffectiveBalanceProperties.class);
                assertThat(p.getMode()).isEqualTo(EffectiveBalanceProperties.Mode.ENFORCED);
                assertThat(p.getOverlayCutoverAt()).isEqualTo(Instant.parse("2020-01-01T00:00:00Z"));
                assertThat(p.getMaxSnapshotAge()).isEqualTo(Duration.ofSeconds(2));});
    }
    @ParameterizedTest @ValueSource(strings={"SHADOW","ENFORCED"})
    void activationWithoutCutoverFailsAtStartup(String mode) {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner().withUserConfiguration(Binding.class)
            .withPropertyValues("trading.effective-balance.mode="+mode)
            .run(c->assertThat(c).hasFailed().getFailure().hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("overlay-cutover-at"));
    }
    @Test void defaultLegacyStartsWithoutCutover() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner().withUserConfiguration(Binding.class)
            .run(c->{assertThat(c).hasNotFailed();
                assertThat(c.getBean(EffectiveBalanceProperties.class).getMode()).isEqualTo(EffectiveBalanceProperties.Mode.LEGACY);
                assertThat(c.getBean(EffectiveBalanceProperties.class).getOverlayCutoverAt()).isNull();});
    }
    @org.springframework.boot.context.properties.EnableConfigurationProperties(EffectiveBalanceProperties.class)
    static class Binding { }
    @Test void invalidTechnicalDurationRefused() { assertThatThrownBy(()->config.setMaxSnapshotAge(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class); }
}
