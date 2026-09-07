package com.saamp.trading.as400;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import static com.saamp.trading.as400.As400SyncFixtures.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class As400SyncWorkerTest {
    private final As400SyncOutboxRepository outbox=mock(As400SyncOutboxRepository.class);
    private final JdbcTemplate postgres=mock(JdbcTemplate.class);
    private final As400MovementGateway gateway=mock(As400MovementGateway.class);
    private final Duration submitted=Duration.ofMinutes(5),accepted=Duration.ofHours(1);
    private final As400SyncWorker worker=new As400SyncWorker(outbox,postgres,gateway,true,submitted,accepted);

    @BeforeEach
    void executeOnlyOwnedClaims() {
        doAnswer(invocation->{
            Consumer<As400SyncEvent> action=invocation.getArgument(1);
            action.accept(invocation.getArgument(0));
            return null;
        }).when(outbox).withClaim(any(),any());
    }

    @Test
    void pendingBecomesSubmittedUsingActualClientExecution() throws Exception {
        var event=due(As400SyncState.PENDING);
        execution("1","57.41702408","EUR");
        worker.processDue();
        verify(gateway).submit(movement(event));
        verify(outbox).advance(event,As400SyncState.SUBMITTED,null,submitted);
        verify(postgres,never()).update(anyString(),any(Object[].class));
    }

    @Test
    void readBackCanAlreadyHaveProvisional() throws Exception {
        var event=due(As400SyncState.PENDING);
        execution("1","57.41702408","EUR");
        when(gateway.submit(any())).thenReturn(904736);
        worker.processDue();
        verify(outbox).advance(event,As400SyncState.ACCEPTED,904736,accepted);
    }

    @Test
    void submittedWithoutProvisionalRemainsSubmitted() {
        var event=due(As400SyncState.SUBMITTED);
        when(gateway.provisional(event)).thenReturn(Optional.of(0));
        worker.processDue();
        verify(outbox).advance(event,As400SyncState.SUBMITTED,null,submitted);
        verify(gateway,never()).submit(any());
        verifyNoInteractions(postgres);
    }

    @Test
    void submittedBecomesAcceptedAndPersistsSiprov() {
        var event=due(As400SyncState.SUBMITTED);
        when(gateway.provisional(event)).thenReturn(Optional.of(904736));
        worker.processDue();
        verify(outbox).advance(event,As400SyncState.ACCEPTED,904736,accepted);
    }

    @Test
    void missingSubmittedMovementNeverTriggersAnotherInsert() {
        var event=due(As400SyncState.SUBMITTED);
        when(gateway.provisional(event)).thenReturn(Optional.empty());
        worker.processDue();
        verify(outbox).markFailed(event,"AS400_SUBMITTED_MOVEMENT_NOT_FOUND",false);
        verify(gateway,never()).submit(any());
    }

    @Test
    void acceptedPendingDefinitiveRemainsAccepted() {
        var event=due(As400SyncState.ACCEPTED);
        when(gateway.settled(event)).thenReturn(false);
        worker.processDue();
        verify(outbox).advance(event,As400SyncState.ACCEPTED,904736,accepted);
        verify(gateway,never()).submit(any());
    }

    @Test
    void acceptedDefinitiveBecomesSettled() {
        var event=due(As400SyncState.ACCEPTED);
        when(gateway.settled(event)).thenReturn(true);
        worker.processDue();
        verify(outbox).advance(event,As400SyncState.SETTLED,904736,accepted);
    }

    @Test
    void zeroWeightFailsBeforeAnyAs400Write() throws Exception {
        var event=due(As400SyncState.PENDING);
        execution("0.000001","57.41702408","EUR");
        worker.processDue();
        verify(outbox).markFailed(event,"AS400_WEIGHT_ROUNDS_TO_ZERO",true);
        verifyNoInteractions(gateway);
    }

    @Test
    void usdFailsExplicitlyWithoutChangingFilled() throws Exception {
        var event=due(As400SyncState.PENDING);
        execution("1","57.41702408","USD");
        worker.processDue();
        verify(outbox).markFailed(event,"AS400_FX_RATE_UNAVAILABLE",true);
        verifyNoInteractions(gateway);
        verify(postgres,never()).update(anyString(),any(Object[].class));
    }

    @ParameterizedTest
    @EnumSource(value=As400SyncState.class,names={"PENDING","SUBMITTED","ACCEPTED"})
    void as400UnavailableOnlySchedulesOutboxRetry(As400SyncState state) throws Exception {
        var event=due(state);
        if (state==As400SyncState.PENDING) execution("1","57.41702408","EUR");
        var failure=new DataAccessResourceFailureException("connection contains secret");
        if (state==As400SyncState.PENDING) when(gateway.submit(any())).thenThrow(failure);
        if (state==As400SyncState.SUBMITTED) when(gateway.provisional(event)).thenThrow(failure);
        if (state==As400SyncState.ACCEPTED) when(gateway.settled(event)).thenThrow(failure);
        worker.processDue();
        verify(outbox).markFailed(event,"AS400_TEMPORARY_DataAccessResourceFailureException",false);
        verify(postgres,never()).update(anyString(),any(Object[].class));
    }

    @Test
    void disabledSyncDoesNotClaimOrCallAs400() {
        new As400SyncWorker(outbox,postgres,gateway,false,submitted,accepted).processDue();
        verifyNoInteractions(outbox,postgres,gateway);
    }

    @Test
    void staleClaimDoesNotCallAs400() {
        due(As400SyncState.PENDING);
        doNothing().when(outbox).withClaim(any(),any());
        worker.processDue();
        verifyNoInteractions(postgres,gateway);
    }

    private As400SyncEvent due(As400SyncState state) {
        var event=event(state);
        when(outbox.claimDue(50,Duration.ofMinutes(5))).thenReturn(List.of(event));
        return event;
    }

    private void execution(String oz,String price,String currency) throws Exception {
        var rs=mock(ResultSet.class);
        when(rs.getString(1)).thenReturn("XAU");
        when(rs.getString(2)).thenReturn("BUY");
        when(rs.getBigDecimal(3)).thenReturn(new BigDecimal(oz));
        when(rs.getBigDecimal(4)).thenReturn(new BigDecimal(price));
        when(rs.getString(5)).thenReturn(currency);
        when(rs.getTimestamp(6)).thenReturn(Timestamp.from(EXECUTED));
        when(postgres.query(anyString(),any(RowMapper.class),any(Object[].class))).thenAnswer(invocation->{
            RowMapper<SicouviMovement> mapper=invocation.getArgument(1);
            return List.of(mapper.mapRow(rs,0));
        });
    }
}
