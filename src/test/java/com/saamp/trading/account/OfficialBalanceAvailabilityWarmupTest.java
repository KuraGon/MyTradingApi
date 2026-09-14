package com.saamp.trading.account;

import com.saamp.trading.domain.*;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.List;
import static org.mockito.Mockito.*;

class OfficialBalanceAvailabilityWarmupTest {
    @Test void warmsMappedActiveLiveAccounts() {
        var repo = mock(AccountRepository.class);
        var balances = mock(EffectiveBalanceService.class);
        var live = new TradingAccount(2,3,Asset.USD,AccountStatus.ACTIVE,null,null,null,"B",null,10002,1,OffsetDateTime.now(),OffsetDateTime.now(),TradingMode.LIVE);
        when(repo.findActiveLiveMapped()).thenReturn(List.of(live));
        new OfficialBalanceAvailabilityWarmup(repo,balances).warmup();
        verify(balances).officialAvailable(live);
    }

    @Test void warmupDoesNotPropagateReaderFailure() {
        var repo = mock(AccountRepository.class);
        var balances = mock(EffectiveBalanceService.class);
        when(repo.findActiveLiveMapped()).thenThrow(new IllegalStateException("db unavailable"));
        new OfficialBalanceAvailabilityWarmup(repo,balances).warmup();
        verifyNoInteractions(balances);
    }
}
