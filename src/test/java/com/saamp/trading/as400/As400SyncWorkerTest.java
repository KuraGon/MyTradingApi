package com.saamp.trading.as400;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import static com.saamp.trading.as400.As400SyncFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class As400SyncWorkerTest {
    private final As400SyncOutboxRepository outbox=mock(As400SyncOutboxRepository.class);
    private final JdbcTemplate postgres=mock(JdbcTemplate.class);
    private final As400MovementGateway gateway=mock(As400MovementGateway.class);
    private final As400FxSource fx=mock(As400FxSource.class);
    private final Duration submitted=Duration.ofMinutes(5),accepted=Duration.ofHours(1);
    private final As400SyncWorker worker=new As400SyncWorker(outbox,postgres,gateway,MAPPING,fx,true,submitted,accepted);
    private boolean owned=true;

    @BeforeEach void executeOnlyOwnedClaims() {
        doAnswer(i->{
            if (owned) { Consumer<As400SyncEvent> action=i.getArgument(1);action.accept(i.getArgument(0)); }
            return null;
        }).when(outbox).withClaim(any(),any());
        doAnswer(i->{owned=false;return null;}).when(outbox).markFailed(any(),anyString(),anyBoolean());
    }

    @Test void preparationCompletesBeforeSubmission() throws Exception {
        var event=due(As400SyncState.PENDING);
        when(outbox.group(event)).thenReturn(new As400MovementGroup(4,List.of()),group(As400SyncState.PENDING));
        execution("1","EUR","EXID-123");
        when(outbox.allocateIdentities(4)).thenReturn(List.of(999996,999997,999998,999999));
        when(gateway.submit(any())).thenReturn(List.of(0,0,0,0));
        worker.processDue();
        var order=inOrder(outbox,gateway);
        order.verify(outbox).freezeFx(event,new BigDecimal("1.00"),null,"EUR_PARITY","EXID-123");
        order.verify(outbox).savePrepared(eq(event),anyList());
        order.verify(outbox).withClaim(eq(event),any());
        order.verify(gateway).submit(group(As400SyncState.PENDING));
        verify(outbox).advance(event,As400SyncState.SUBMITTED,submitted);
        verifyNoInteractions(fx);
    }

    @ParameterizedTest @ValueSource(strings={"EUR","USD"})
    void retryDoesNotRepriceReallocateOrFetchFx(String currency) {
        var event=due(As400SyncState.PENDING);
        var data=As400SyncFixtures.execution(com.saamp.trading.domain.OrderSide.BUY,currency,"EXID-123")
                .build(event,MAPPING,new BigDecimal(currency.equals("EUR")?"1.00":"1.17"),List.of(999996,999997,999998,999999));
        var original=group(As400SyncState.PENDING);
        var frozen=new As400MovementGroup(4,java.util.stream.IntStream.range(0,4).mapToObj(i ->
                new As400Movement(i+1,i,original.legs().get(i).legRole(),data.get(i),As400SyncState.PENDING,null)).toList());
        when(outbox.group(event)).thenReturn(frozen);
        when(gateway.submit(any())).thenReturn(List.of(0,0,0,0));
        worker.processDue();
        worker.processDue();
        verify(gateway,times(2)).submit(frozen);
        verify(outbox,never()).allocateIdentities(anyInt());
        verify(outbox,never()).freezeFx(any(),any(),any(),any(),any());
        verifyNoInteractions(postgres,fx);
    }

    @Test void usdFreezesFxBeforeSavingGroup() throws Exception {
        var event=due(As400SyncState.PENDING);
        when(outbox.group(event)).thenReturn(new As400MovementGroup(4,List.of()),group(As400SyncState.PENDING));
        when(outbox.allocateIdentities(4)).thenReturn(List.of(1,2,3,4));
        when(fx.fetch()).thenReturn(new As400FxSource.FrozenFx(new BigDecimal("1.17"),"PROVIDER:MID"));
        execution("1","USD","EXID-123");
        when(gateway.submit(any())).thenReturn(List.of(0,0,0,0));
        worker.processDue();
        verify(outbox).freezeFx(event,new BigDecimal("1.17"),"EURUSD","PROVIDER:MID","EXID-123");
        var captor=org.mockito.ArgumentCaptor.forClass(List.class);
        verify(outbox).savePrepared(eq(event),captor.capture());
        List<SicouviMovement> legs=captor.getValue();
        assertThat(legs).allSatisfy(m->assertThat(m.fx()).isEqualTo(new BigDecimal("1.17")));
        assertThat(legs.get(0).quotation()).isEqualTo(new BigDecimal("1578.0000"));
        assertThat(legs.get(3).nucli()).isEqualTo(15268);
        assertThat(legs.get(3).quotation()).isEqualTo(new BigDecimal("1539.0000"));
    }

    @Test void persistedFxIsReusedEvenWhenGroupStillNeedsPreparation() throws Exception {
        var event=due(As400SyncState.PENDING);
        when(outbox.group(event)).thenReturn(new As400MovementGroup(4,List.of()),group(As400SyncState.PENDING));
        when(outbox.frozenFx(event)).thenReturn(new BigDecimal("1.17"));
        when(outbox.allocateIdentities(4)).thenReturn(List.of(1,2,3,4));
        when(gateway.submit(any())).thenReturn(List.of(0,0,0,0));
        execution("1","USD","EXID-123");
        worker.processDue();
        verifyNoInteractions(fx);
        verify(outbox,never()).freezeFx(any(),any(),any(),any(),any());
    }

    @Test void unavailableFxIsRetryableAndSendsNothing() throws Exception {
        var event=due(As400SyncState.PENDING);
        when(outbox.group(event)).thenReturn(new As400MovementGroup(4,List.of()));
        execution("1","USD","EXID-123");
        when(fx.fetch()).thenThrow(new IllegalStateException("AS400_FX_RATE_UNAVAILABLE"));
        worker.processDue();
        verify(outbox).markFailed(event,"AS400_FX_RATE_UNAVAILABLE",false);
        verifyNoInteractions(gateway);
        verify(outbox,never()).savePrepared(any(),any());
    }

    @ParameterizedTest @ValueSource(strings={""," ","123456789012345678901"})
    void invalidExidBlocksBeforeAnyDb2Write(String exid) throws Exception {
        var event=due(As400SyncState.PENDING);
        when(outbox.group(event)).thenReturn(new As400MovementGroup(4,List.of()));
        execution("1","EUR",exid);
        worker.processDue();
        verify(outbox).markFailed(event,exid.length()>20?"AS400_EXID_TOO_LONG":"AS400_EXID_MISSING",true);
        verifyNoInteractions(gateway,fx);
    }

    @Test void zeroWeightBlocksBeforeAnyDb2Write() throws Exception {
        var event=due(As400SyncState.PENDING);
        when(outbox.group(event)).thenReturn(new As400MovementGroup(4,List.of()));
        execution("0.000001","EUR","EXID-123");
        worker.processDue();
        verify(outbox).markFailed(event,"AS400_WEIGHT_ROUNDS_TO_ZERO",true);
        verifyNoInteractions(gateway);
    }

    @ParameterizedTest @ValueSource(ints={1,2,3})
    void expectedCountBlocksIncompletePreparedGroup(int count) {
        var event=due(As400SyncState.PENDING);
        when(outbox.group(event)).thenReturn(new As400MovementGroup(4,group(As400SyncState.PENDING).legs().subList(0,count)));
        worker.processDue();
        verify(outbox).markFailed(event,"AS400_GROUP_INCOMPLETE",true);
        verifyNoInteractions(gateway);
        verify(outbox,never()).advance(any(),any(),any());
    }

    @ParameterizedTest @ValueSource(ints={0,1,2,3,4})
    void acceptedRequiresEveryProvisional(int received) {
        var event=due(As400SyncState.SUBMITTED);
        when(gateway.provisional(any())).thenReturn(java.util.stream.IntStream.range(0,4)
                .mapToObj(i->i<received?904736+i:0).toList());
        worker.processDue();
        verify(outbox).advance(event,received==4?As400SyncState.ACCEPTED:As400SyncState.SUBMITTED,
                received==4?accepted:submitted);
        verify(gateway,never()).submit(any());
    }

    @ParameterizedTest @ValueSource(ints={0,1,2,3,4})
    void settledRequiresEveryDefinitiveLeg(int count) {
        var event=due(As400SyncState.ACCEPTED);
        when(gateway.settled(any())).thenAnswer(i->((As400Movement)i.getArgument(0)).legIndex()<count);
        worker.processDue();
        verify(outbox).advance(event,count==4?As400SyncState.SETTLED:As400SyncState.ACCEPTED,accepted);
        verify(gateway,never()).submit(any());
    }

    @Test void readBackCanAlreadyHaveAllProvisionals() {
        var event=due(As400SyncState.PENDING);
        when(gateway.submit(any())).thenReturn(List.of(1,2,3,4));
        worker.processDue();
        verify(outbox).advance(event,As400SyncState.ACCEPTED,accepted);
    }

    @Test void partialReadBackBlocksAndNeverRepairs() {
        var event=due(As400SyncState.PENDING);
        when(gateway.submit(any())).thenThrow(new As400SyncDataException("AS400_PARTIAL_GROUP_REQUIRES_REVIEW"));
        worker.processDue();
        verify(outbox).markFailed(event,"AS400_PARTIAL_GROUP_REQUIRES_REVIEW",true);
        verify(outbox,never()).savePrepared(any(),any());
    }

    @ParameterizedTest @EnumSource(value=As400SyncState.class,names={"PENDING","SUBMITTED","ACCEPTED"})
    void as400UnavailableOnlySchedulesOutboxRetry(As400SyncState state) {
        var event=due(state);
        var failure=new DataAccessResourceFailureException("connection contains secret");
        if (state==As400SyncState.PENDING) when(gateway.submit(any())).thenThrow(failure);
        if (state==As400SyncState.SUBMITTED) when(gateway.provisional(any())).thenThrow(failure);
        if (state==As400SyncState.ACCEPTED) when(gateway.settled(any())).thenThrow(failure);
        worker.processDue();
        verify(outbox).markFailed(event,"AS400_TEMPORARY_DataAccessResourceFailureException",false);
        verify(postgres,never()).update(anyString(),any(Object[].class));
    }

    @Test void disabledSyncDoesNotClaimOrCallAs400() {
        new As400SyncWorker(outbox,postgres,gateway,MAPPING,fx,false,submitted,accepted).processDue();
        verifyNoInteractions(outbox,postgres,gateway,fx);
    }

    @Test void staleClaimDoesNotCallAs400() {
        due(As400SyncState.PENDING);
        owned=false;
        worker.processDue();
        verifyNoInteractions(postgres,gateway,fx);
    }

    @Test void reclaimedBetweenPreparationAndSubmissionNeverCallsDb2() throws Exception {
        var event=due(As400SyncState.PENDING);
        when(outbox.group(event)).thenReturn(new As400MovementGroup(4,List.of()));
        when(outbox.allocateIdentities(4)).thenReturn(List.of(1,2,3,4));
        execution("1","EUR","EXID-123");
        doAnswer(i->{owned=false;return null;}).when(outbox).savePrepared(any(),any());
        worker.processDue();
        verifyNoInteractions(gateway);
    }

    private As400SyncEvent due(As400SyncState state) {
        var event=event(state);
        when(outbox.claimDue(50,Duration.ofMinutes(5))).thenReturn(List.of(event));
        when(outbox.group(event)).thenReturn(group(state));
        return event;
    }

    private void execution(String oz,String currency,String exid) throws Exception {
        var rs=mock(ResultSet.class);
        when(rs.getString(1)).thenReturn("XAU");
        when(rs.getString(2)).thenReturn("BUY");
        when(rs.getBigDecimal(3)).thenReturn(new BigDecimal(oz));
        when(rs.getBigDecimal(4)).thenReturn(new BigDecimal("57.41702408"));
        when(rs.getBigDecimal(5)).thenReturn(new BigDecimal("56"));
        when(rs.getString(6)).thenReturn(currency);
        when(rs.getTimestamp(7)).thenReturn(Timestamp.from(EXECUTED));
        when(rs.getString(8)).thenReturn(exid);
        when(postgres.query(anyString(),any(RowMapper.class),any(Object[].class))).thenAnswer(i->{
            RowMapper<As400GroupPreparation> mapper=i.getArgument(1);
            return List.of(mapper.mapRow(rs,0));
        });
    }
}
