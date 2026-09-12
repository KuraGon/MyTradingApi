package com.saamp.trading.account;

import com.saamp.trading.common.TradingException;
import com.saamp.trading.domain.AccountStatus;
import com.saamp.trading.domain.Asset;
import com.saamp.trading.domain.TradingMode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DemoEffectiveBalanceServiceTest {
    private final BalanceRepository live = mock(BalanceRepository.class);
    private final DemoBalanceRepository demo = mock(DemoBalanceRepository.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final PendingTradingAdjustmentRepository pending = mock(PendingTradingAdjustmentRepository.class);
    private final OfficialTradingBalanceReader official = mock(OfficialTradingBalanceReader.class);
    private final EffectiveBalanceProperties properties = new EffectiveBalanceProperties();
    private final TradingAccount account = new TradingAccount(1L, 2L, Asset.EUR, AccountStatus.ACTIVE,
            null, null, null, "B", null, 20662, 1, OffsetDateTime.now(), OffsetDateTime.now());
    private EffectiveBalanceService service;

    @BeforeEach
    void setUp() {
        properties.setMode(EffectiveBalanceProperties.Mode.ENFORCED);
        properties.setOverlayCutoverAt(Instant.parse("2026-01-01T00:00:00Z"));
        service = new EffectiveBalanceService(live, accounts, pending, official, properties, demo, new SimpleMeterRegistry());
    }

    @Test
    void demoUsesItsLocalProjectionWhenTheOfficialReaderIsUnavailable() {
        List<Balance> projection = List.of(
                new Balance(1L, Asset.EUR, new BigDecimal("250.000000"), OffsetDateTime.now()),
                new Balance(1L, Asset.XAU, new BigDecimal("1.000000"), OffsetDateTime.now()));
        var demoAccount = new TradingAccount(1L,2L,Asset.EUR,AccountStatus.ACTIVE,null,null,null,
                null,null,null,1,OffsetDateTime.now(),OffsetDateTime.now(),TradingMode.DEMO);
        when(accounts.findById(1L)).thenReturn(java.util.Optional.of(demoAccount));
        when(demo.findAll(1L)).thenReturn(projection);
        when(official.read(any(), anyList())).thenThrow(new IllegalStateException("AS400 unavailable"));

        EffectiveBalanceSnapshot snapshot = service.captureForOperation(demoAccount, TradingMode.DEMO);

        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.decisionBalances()).isEqualTo(projection);
        assertThat(snapshot.calculatedEffectiveBalances()).isEqualTo(projection);
        assertThat(service.findAll(1L, TradingMode.DEMO)).isEqualTo(projection);
        assertThat(service.validate(demoAccount, snapshot, TradingMode.DEMO)).isEqualTo(projection);
        verify(demo, times(3)).findAll(1L);
        verifyNoInteractions(official, pending, live);
    }

    @Test
    void liveEnforcedStillFailsClosedWhenTheOfficialReaderIsUnavailable() {
        when(pending.read(1L)).thenReturn(List.of());
        when(official.read(any(), anyList())).thenThrow(new IllegalStateException("AS400 unavailable"));

        assertThatThrownBy(() -> service.captureForOperation(account, TradingMode.LIVE))
                .isInstanceOfSatisfying(TradingException.class,
                        error -> assertThat(error.getCode()).isEqualTo("OFFICIAL_BALANCE_UNAVAILABLE"));
        verify(official).read(eq(account), anyList());
        verifyNoInteractions(demo);
    }

    @Test void durableDemoAccountBypassesOfficialEvenWithoutAnExplicitModeArgument() {
        var demoAccount=new TradingAccount(1,2,Asset.EUR,AccountStatus.ACTIVE,null,null,null,
                null,null,null,1,OffsetDateTime.now(),OffsetDateTime.now(),TradingMode.DEMO);
        properties.setOverlayCutoverAt(null);
        when(demo.findAll(1)).thenReturn(List.of());
        assertThat(service.captureForOperation(demoAccount).available()).isTrue();
        assertThat(service.capture(demoAccount).available()).isTrue();
        verifyNoInteractions(official,pending,live);
        assertThatThrownBy(()->new JdbcOfficialTradingBalanceReader(null,null).read(demoAccount,List.of()))
                .isInstanceOf(TradingException.class);
    }
}
