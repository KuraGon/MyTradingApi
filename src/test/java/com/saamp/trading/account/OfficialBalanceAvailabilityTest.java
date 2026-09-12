package com.saamp.trading.account;

import com.saamp.trading.domain.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OfficialBalanceAvailabilityTest {
    private final OfficialBalanceAvailability state = new OfficialBalanceAvailability();
    private final Instant now = Instant.parse("2026-09-12T10:00:00Z");
    static TradingAccount account(long id, TradingMode mode) {
        return new TradingAccount(id,id,Asset.EUR,AccountStatus.ACTIVE,null,null,null,"B",null,20662,
                1,OffsetDateTime.now(),OffsetDateTime.now(),mode);
    }
    @Test void unknownExpiredAndClockRollbackNeverMeanOpen() {
        var account=account(1,TradingMode.LIVE);
        assertThat(state.recent(account,now)).isNull();
        state.record(account,now,now,true);
        assertThat(state.recent(account,now.plusSeconds(44))).isTrue();
        assertThat(state.recent(account,now.plusSeconds(45))).isNull();
        assertThat(state.recent(account,now.minusSeconds(1))).isNull();
        assertThat(state.refreshDue(account,now.plusSeconds(15))).isTrue();
    }
    @Test void failureRecoveryAndOutOfOrderCompletions() {
        var account=account(1,TradingMode.LIVE);
        state.record(account,now,now,true);
        state.record(account,now.plusSeconds(1),now.plusSeconds(1),false);
        state.record(account,now,now,true);
        assertThat(state.recent(account,now.plusSeconds(2))).isFalse();
        state.record(account,now.plusSeconds(3),now.plusSeconds(3),true);
        assertThat(state.recent(account,now.plusSeconds(4))).isTrue();
    }
    @Test void anOverlappingSuccessCannotEraseAFailureObservedWhileItWasRunning() {
        var account=account(1,TradingMode.LIVE);
        state.record(account,now,now.plusSeconds(1),true);
        state.record(account,now.minusSeconds(1),now.plusSeconds(2),false);
        state.record(account,now,now.plusSeconds(3),true);
        assertThat(state.recent(account,now.plusSeconds(4))).isFalse();
        state.record(account,now.plusSeconds(5),now.plusSeconds(6),true);
        assertThat(state.recent(account,now.plusSeconds(7))).isTrue();
    }
    @Test void observationCannotBeSharedAcrossAccountsOrChangedMapping() {
        var account=account(1,TradingMode.LIVE);
        state.record(account,now,now,true);
        assertThat(state.recent(account(2,TradingMode.LIVE),now)).isNull();
        var changed=new TradingAccount(1,1,Asset.EUR,AccountStatus.ACTIVE,null,null,null,"B",null,99999,
                2,account.createdAt(),account.updatedAt(),TradingMode.LIVE);
        assertThat(state.recent(changed,now)).isNull();
    }
}
