package com.saamp.trading.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.saamp.trading.as400.As400FxSource;
import com.saamp.trading.domain.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class OfficialReadinessProbeTest {
    private static final OffsetDateTime NOW = OffsetDateTime.now();
    private static TradingAccount account(Asset currency) {
        return new TradingAccount(2,3,currency,AccountStatus.ACTIVE,null,null,null,"B",null,10002,1,NOW,NOW,TradingMode.LIVE);
    }
    private static OfficialTradingBalanceReader.Reading reading() {
        var b=new EnumMap<Asset,BigDecimal>(Asset.class);
        b.put(Asset.EUR,new BigDecimal("100.00")); b.put(Asset.XAU,BigDecimal.ZERO); b.put(Asset.XAG,BigDecimal.ZERO);
        b.put(Asset.XPT,BigDecimal.ZERO); b.put(Asset.XPD,BigDecimal.ZERO);
        return new OfficialTradingBalanceReader.Reading(b,Map.of());
    }
    private EffectiveBalanceService service(OfficialTradingBalanceReader official, As400FxSource fx) {
        var p=mock(BalanceRepository.class); var a=mock(AccountRepository.class); var f=mock(PendingTradingAdjustmentRepository.class);
        var c=new EffectiveBalanceProperties(); c.setMode(EffectiveBalanceProperties.Mode.ENFORCED); c.setOverlayCutoverAt(Instant.parse("2020-01-01T00:00:00Z"));
        return new EffectiveBalanceService(p,a,f,official,c,null,new SimpleMeterRegistry(),fx);
    }
    @Test void eurReadinessUsesOneOfficialRead() {
        var official=mock(OfficialTradingBalanceReader.class); when(official.readDiagnostic(any(),anyList())).thenReturn(reading());
        var service=service(official,null);
        assertThat(service.probeOfficialReadiness(account(Asset.EUR))).isTrue();
        verify(official,times(1)).readDiagnostic(any(),anyList());
    }
    @Test void usdReadinessRequiresFreshFx() {
        var official=mock(OfficialTradingBalanceReader.class); when(official.readDiagnostic(any(),anyList())).thenReturn(reading());
        var fx=mock(As400FxSource.class); when(fx.fetchValuation()).thenReturn(new As400FxSource.FrozenFx(new BigDecimal("1.17"),"test"));
        assertThat(service(official,fx).probeOfficialReadiness(account(Asset.USD))).isTrue();
        verify(fx).fetchValuation();
    }
    @Test void usdReadinessFailsWhenFxUnavailable() {
        var official=mock(OfficialTradingBalanceReader.class); when(official.readDiagnostic(any(),anyList())).thenReturn(reading());
        var fx=mock(As400FxSource.class); when(fx.fetchValuation()).thenThrow(new IllegalStateException("FX stale"));
        assertThat(service(official,fx).probeOfficialReadiness(account(Asset.USD))).isFalse();
        verify(official,times(3)).readDiagnostic(any(),anyList());
    }
}
