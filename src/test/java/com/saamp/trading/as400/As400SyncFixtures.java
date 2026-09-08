package com.saamp.trading.as400;

import com.saamp.trading.domain.Asset;
import com.saamp.trading.domain.OrderSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

final class As400SyncFixtures {
    static final Instant EXECUTED=Instant.parse("2026-07-06T22:15:42Z");
    static final As400TradingMapping MAPPING=new As400TradingMapping("B","I",15001,15002,15267,15268);
    static As400SyncEvent event(As400SyncState state) {
        return new As400SyncEvent(1,12345,As400SyncTarget.SICOUVI,0,UUID.randomUUID(),state,"B",123456);
    }
    static As400GroupPreparation execution(OrderSide side,String currency,String exid) {
        return new As400GroupPreparation(Asset.XAU,side,new BigDecimal("1.000000"),
                new BigDecimal("57.41702408"),new BigDecimal("56"),currency,EXECUTED,exid);
    }
    static As400MovementGroup group(As400SyncState state) {
        var data=execution(OrderSide.BUY,"EUR","EXID-123").build(event(state),MAPPING,
                new BigDecimal("1.00"),List.of(999996,999997,999998,999999));
        String[] roles={"CLIENT","INTERCO_LFMP","INTERCO_SAAMP","STONEX"};
        return new As400MovementGroup(4,IntStream.range(0,4).mapToObj(i ->
                new As400Movement(i+1,i,roles[i],data.get(i),state,
                        state==As400SyncState.ACCEPTED || state==As400SyncState.SETTLED?904736+i:null)).toList());
    }
    static SicouviMovement movement(As400SyncEvent event) {
        return SicouviMovement.from(event.ste(),999999,event.nucliTrading(),Asset.XAU,"V",BigDecimal.ONE,
                new BigDecimal("57.41702408"),new BigDecimal("1.00"),EXECUTED,"EXID-123");
    }
}
