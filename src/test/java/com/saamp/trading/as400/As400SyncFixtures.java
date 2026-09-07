package com.saamp.trading.as400;

import com.saamp.trading.domain.Asset;
import com.saamp.trading.domain.OrderSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

final class As400SyncFixtures {
    static final Instant EXECUTED=Instant.parse("2026-07-06T22:15:42Z");
    static As400SyncEvent event(As400SyncState state) {
        return new As400SyncEvent(1,12345,As400SyncTarget.SICOUVI,0,UUID.randomUUID(),state,999999,
                state==As400SyncState.ACCEPTED?904736:null,"i",123456);
    }
    static SicouviMovement movement(As400SyncEvent event) {
        return SicouviMovement.from(event,Asset.XAU,OrderSide.BUY,new BigDecimal("1.000000"),
                new BigDecimal("57.41702408"),"EUR",EXECUTED);
    }
}
