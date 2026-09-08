package com.saamp.trading.as400;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static com.saamp.trading.as400.As400SyncFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcAs400MovementGatewayTest {
    private final JdbcOperations jdbc=mock(JdbcOperations.class);
    private final As400MovementGroup pending=group(As400SyncState.PENDING);
    private final JdbcAs400MovementGateway gateway=new JdbcAs400MovementGateway(jdbc,
            Clock.fixed(Instant.parse("2026-07-08T10:11:12Z"),ZoneOffset.UTC),
            org.springframework.transaction.support.TransactionOperations.withoutTransaction());

    @Test void insertsFourMovementsWithHistoricalColumns() {
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
        assertThat(gateway.submit(pending)).containsExactly(0,0,0,0);
        var sql=ArgumentCaptor.forClass(String.class);
        var args=ArgumentCaptor.forClass(Object[].class);
        verify(jdbc,times(4)).update(sql.capture(),args.capture());
        for (int i=0;i<4;i++) {
            var m=pending.legs().get(i).data();
            assertThat(sql.getAllValues().get(i)).contains("INSERT INTO SPECIF1.SICOUVI1","'20'","'MYTRADING'")
                    .doesNotContain("CLCPDP03","PCGMLFCM","ETPRO2");
            assertThat(args.getAllValues().get(i)).containsExactly(m.ste(),m.sicoui(),m.nucli(),260707,1542,260707,
                    m.side(),m.metal(),m.condition(),m.grams(),m.fx(),m.quotation(),m.ref2(),"EXID-123",260708,121112);
        }
        var order=inOrder(jdbc);
        order.verify(jdbc,times(4)).query(anyString(),any(RowMapper.class),any(Object[].class));
        order.verify(jdbc,times(4)).update(anyString(),any(Object[].class));
    }

    @Test void existingCompleteGroupNeverInsertsAgain() {
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of(904736));
        assertThat(gateway.submit(pending)).containsExactly(904736,904736,904736,904736);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @ParameterizedTest @ValueSource(ints={1,2,3})
    void existingPartialGroupBlocksWithoutRepair(int found) {
        var calls=new AtomicInteger();
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class)))
                .thenAnswer(i -> calls.getAndIncrement()<found?List.of(0):List.of());
        assertThatThrownBy(()->gateway.submit(pending)).hasMessage("AS400_PARTIAL_GROUP_REQUIRES_REVIEW");
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @ParameterizedTest @ValueSource(ints={1,2,3})
    void ambiguousErrorAndPartialReadBackBlocksWithoutRepair(int found) {
        var calls=new AtomicInteger();
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class)))
                .thenAnswer(i -> {int n=calls.getAndIncrement();return n>=4 && n<4+found?List.of(0):List.of();});
        when(jdbc.update(anyString(),any(Object[].class))).thenThrow(new DataAccessResourceFailureException("lost"));
        assertThatThrownBy(()->gateway.submit(pending)).hasMessage("AS400_PARTIAL_GROUP_REQUIRES_REVIEW");
        verify(jdbc,times(1)).update(anyString(),any(Object[].class));
    }

    @Test void unconfirmedInsertRemainsRetryable() {
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        when(jdbc.update(anyString(),any(Object[].class))).thenThrow(new DataAccessResourceFailureException("lost"));
        assertThatThrownBy(()->gateway.submit(pending)).isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test void failedReadBackDoesNotAssumeSuccess() {
        var calls=new AtomicInteger();
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenAnswer(i->{
            if (calls.getAndIncrement()>=4) throw new DataAccessResourceFailureException("read unavailable");
            return List.of();
        });
        when(jdbc.update(anyString(),any(Object[].class))).thenThrow(new DataAccessResourceFailureException("lost"));
        assertThatThrownBy(()->gateway.submit(pending)).hasMessage("lost");
    }

    @Test void ambiguousCorrelationFailsWithoutInsert() {
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of(0,0));
        assertThatThrownBy(()->gateway.submit(pending)).hasMessage("AS400_MOVEMENT_AMBIGUOUS");
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test void lookupChecksBusinessDataIncludingSide() throws Exception {
        var m=pending.legs().getFirst().data();
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenAnswer(i->{
            var rs=mock(java.sql.ResultSet.class);
            when(rs.getString(2)).thenReturn("A");
            RowMapper<Integer> mapper=i.getArgument(1);
            return List.of(mapper.mapRow(rs,0));
        });
        assertThatThrownBy(()->gateway.submit(pending)).hasMessage("AS400_MOVEMENT_DATA_MISMATCH");
        verify(jdbc).query(contains("SISTE=? AND SICOUI=? AND SICLI=? AND SIREF3=?"),any(RowMapper.class),
                eq(m.ste()),eq(m.sicoui()),eq(m.nucli()),eq(m.ref3()));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test void submittedPollReturnsCurrentProvisionalForEveryLeg() {
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of(904736));
        assertThat(gateway.provisional(group(As400SyncState.SUBMITTED))).containsExactly(904736,904736,904736,904736);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test void missingSubmittedGroupNeverTriggersInsert() {
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        assertThatThrownBy(()->gateway.provisional(group(As400SyncState.SUBMITTED))).hasMessage("AS400_SUBMITTED_GROUP_NOT_FOUND");
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test void absentProvisionalDoesNotSettle() {
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        assertThat(gateway.settled(group(As400SyncState.ACCEPTED).legs().getFirst())).isFalse();
    }

    @ParameterizedTest @ValueSource(strings={" ","","N"})
    void onlyEtpro1OCanSettle(String etpro1) {
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of(etpro1));
        assertThat(gateway.settled(group(As400SyncState.ACCEPTED).legs().getFirst())).isFalse();
    }

    @Test void definitiveProvisionalSettlesWithFullClientCorrelation() {
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of("O"));
        assertThat(gateway.settled(group(As400SyncState.ACCEPTED).legs().getFirst())).isTrue();
        verify(jdbc).query(contains("WHERE STE=? AND NUPROV=? AND NUCLI=?"),
                any(RowMapper.class),eq("B"),eq(904736),eq(123456));
    }

    @Test void etpro2OIsIgnoredWhenEtpro1IsBlank() throws Exception {
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenAnswer(i->{
            assertThat((String)i.getArgument(0)).doesNotContain("ETPRO2");
            var rs=mock(java.sql.ResultSet.class);
            when(rs.getString(1)).thenReturn(" ");
            when(rs.getString("ETPRO2")).thenReturn("O");
            RowMapper<String> mapper=i.getArgument(1);
            return List.of(mapper.mapRow(rs,0));
        });
        assertThat(gateway.settled(group(As400SyncState.ACCEPTED).legs().getFirst())).isFalse();
    }

    @Test void thirdInsertFailureRollsBackEveryLegBeforeReadBack() throws Exception {
        var harness=new TransactionHarness();
        when(harness.jdbc.update(anyString(),any(Object[].class))).thenAnswer(i->{
            if (harness.staged.size()==2) throw new DataAccessResourceFailureException("third insert");
            harness.staged.add(1); return 1;
        });
        assertThatThrownBy(()->harness.gateway.submit(pending)).hasMessage("third insert");
        assertThat(harness.committed).isEmpty();
        assertThat(harness.staged).isEmpty();
        verify(harness.connection).rollback();
        // Seule la transaction de lecture est commitee.
        verify(harness.connection).commit();
        verify(harness.jdbc,times(3)).update(anyString(),any(Object[].class));
    }

    @Test void lostCommitResponseVerifiedInNewReadCommittedTransactionWithoutResend() throws Exception {
        var harness=new TransactionHarness();
        doAnswer(i->{
            harness.committed.addAll(harness.staged);
            harness.staged.clear();
            throw new java.sql.SQLException("commit response lost");
        }).doNothing().when(harness.connection).commit();
        assertThat(harness.gateway.submit(pending)).containsExactly(0,0,0,0);
        assertThat(harness.committed).hasSize(4);
        verify(harness.connection,times(2)).setTransactionIsolation(java.sql.Connection.TRANSACTION_READ_COMMITTED);
        verify(harness.connection,times(2)).setAutoCommit(false);
        verify(harness.connection,times(2)).commit();
        verify(harness.jdbc,times(4)).update(anyString(),any(Object[].class));
        verify(harness.jdbc,times(8)).query(anyString(),any(RowMapper.class),any(Object[].class));
        assertThat(harness.gateway.submit(pending)).containsExactly(0,0,0,0);
        verify(harness.jdbc,times(4)).update(anyString(),any(Object[].class));
    }

    @Test void completeGroupCommitsOnlyOnce() throws Exception {
        var harness=new TransactionHarness();
        assertThat(harness.gateway.submit(pending)).containsExactly(0,0,0,0);
        assertThat(harness.committed).hasSize(4);
        verify(harness.connection).commit();
        verify(harness.connection,never()).rollback();
    }

    private static class TransactionHarness {
        final java.sql.Connection connection=mock(java.sql.Connection.class);
        final JdbcTemplate jdbc=mock(JdbcTemplate.class);
        final List<Integer> staged=new ArrayList<>(),committed=new ArrayList<>();
        final As400MovementGateway gateway;
        TransactionHarness() throws Exception {
            var ds=mock(javax.sql.DataSource.class);
            when(ds.getConnection()).thenReturn(connection);
            when(connection.getAutoCommit()).thenReturn(true);
            when(connection.getTransactionIsolation()).thenReturn(java.sql.Connection.TRANSACTION_READ_UNCOMMITTED);
            when(jdbc.getDataSource()).thenReturn(ds);
            when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class)))
                    .thenAnswer(i->committed.size()==4?List.of(0):List.of());
            when(jdbc.update(anyString(),any(Object[].class))).thenAnswer(i->{staged.add(1);return 1;});
            doAnswer(i->{committed.addAll(staged);staged.clear();return null;}).when(connection).commit();
            doAnswer(i->{staged.clear();return null;}).when(connection).rollback();
            gateway=new As400Configuration().as400MovementGateway(jdbc);
        }
    }
}
