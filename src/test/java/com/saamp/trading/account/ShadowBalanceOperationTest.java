package com.saamp.trading.account;

import com.saamp.trading.domain.Asset;
import com.saamp.trading.domain.AccountStatus;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShadowBalanceOperationTest {
    @Test
    void blockedOfficialReaderCannotDelayOrChangeLegacyDecision() throws Exception {
        var projection=mock(BalanceRepository.class);
        var accounts=mock(AccountRepository.class);
        var overlay=mock(PendingTradingAdjustmentRepository.class);
        var official=mock(OfficialTradingBalanceReader.class);
        var config=new EffectiveBalanceProperties();
        config.setMode(EffectiveBalanceProperties.Mode.SHADOW);
        config.setOverlayCutoverAt(Instant.parse("2026-01-01T00:00:00Z"));
        var account=new TradingAccount(1,1,Asset.EUR,AccountStatus.ACTIVE,null,null,null,1,
                OffsetDateTime.now(),OffsetDateTime.now());
        var legacy=List.of(new Balance(1,Asset.EUR,BigDecimal.TEN,OffsetDateTime.now()));
        when(projection.findAll(1)).thenReturn(legacy);
        when(overlay.read(1)).thenReturn(List.of());
        var entered=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        when(official.read(any(),anyList())).thenAnswer(invocation->{
            entered.countDown();
            release.await(5,TimeUnit.SECONDS);
            throw new IllegalStateException("test timeout");
        });
        var service=new EffectiveBalanceService(projection,accounts,overlay,official,config);
        try {
            var first=assertTimeoutPreemptively(Duration.ofSeconds(1),()->service.captureForOperation(account));
            assertThat(first.decisionBalances()).isEqualTo(legacy);
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            var second=assertTimeoutPreemptively(Duration.ofSeconds(1),()->service.captureForOperation(account));
            assertThat(second.decisionBalances()).isEqualTo(legacy);
            verify(official,times(1)).read(any(),anyList());
        } finally { release.countDown(); service.close(); }
    }

    @Test
    void failedDiagnosticOpensCircuitWithoutQueueingOrCachingBalances() {
        ExecutorService executor=mock(ExecutorService.class);
        AtomicReference<Runnable> task=new AtomicReference<>();
        doAnswer(call->{task.set(call.getArgument(0));return null;}).when(executor).execute(any());
        try(var diagnostics=new ShadowBalanceDiagnostics(executor,()->0L,Duration.ofSeconds(30))) {
            assertThat(diagnostics.submit(()->false)).isEqualTo("DIAGNOSTIC_PENDING");
            assertThat(diagnostics.submit(()->true)).isEqualTo("DIAGNOSTIC_BUSY");
            task.get().run();
            assertThat(diagnostics.submit(()->true)).isEqualTo("CIRCUIT_OPEN");
            verify(executor,times(1)).execute(any());
        }
    }
}
