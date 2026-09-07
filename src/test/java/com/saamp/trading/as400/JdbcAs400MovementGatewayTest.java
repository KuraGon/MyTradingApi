package com.saamp.trading.as400;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.math.BigDecimal;
import static com.saamp.trading.as400.As400SyncFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcAs400MovementGatewayTest {
    private final JdbcOperations jdbc=mock(JdbcOperations.class);
    private final JdbcAs400MovementGateway gateway=new JdbcAs400MovementGateway(jdbc,
            Clock.fixed(Instant.parse("2026-07-08T10:11:12Z"),ZoneOffset.UTC),org.springframework.transaction.support.TransactionOperations.withoutTransaction());

    @Test
    void insertsExactlyOneClientMovementWithAllColumns() {
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
        var movement=movement(event(As400SyncState.PENDING));
        assertThat(gateway.submit(movement)).isZero();
        var sql=ArgumentCaptor.forClass(String.class);
        var args=ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(),args.capture());
        assertThat(sql.getValue()).contains("INSERT INTO SPECIF1.SICOUVI1","'20'","'MYTRADING'")
                .doesNotContain("CLCPDP03","PCGMLFCM","ETPRO2");
        assertThat(args.getValue()).containsExactly("i",999999,123456,260707,1542,260707,"V","O","OR SPOT",
                new BigDecimal("31.10"),new BigDecimal("1.00000"),new BigDecimal("1846.0000"),
                "OR SPOT","MT-9IX",260708,121112);
        assertThat(sql.getValue().chars().filter(c->c=='?').count()).isEqualTo(args.getValue().length);
        verify(jdbc).query(contains("SISTE=? AND SICOUI=? AND SICLI=? AND SIREF3=?"),
                any(RowMapper.class),eq("i"),eq(999999),eq(123456),eq("MT-9IX"));
    }

    @Test
    void existingMovementNeverInsertsAgain() {
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of(904736));
        assertThat(gateway.submit(movement(event(As400SyncState.PENDING)))).isEqualTo(904736);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test
    void lostInsertResponseConfirmedByReadBackIsSubmitted() {
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of(),List.of(0));
        when(jdbc.update(anyString(),any(Object[].class))).thenThrow(new DataAccessResourceFailureException("lost response"));
        assertThat(gateway.submit(movement(event(As400SyncState.PENDING)))).isZero();
        verify(jdbc,times(2)).query(anyString(),any(RowMapper.class),any(Object[].class));
        verify(jdbc,times(1)).update(anyString(),any(Object[].class));
    }

    @Test
    void unconfirmedInsertRemainsRetryable() {
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        when(jdbc.update(anyString(),any(Object[].class))).thenThrow(new DataAccessResourceFailureException("lost response"));
        assertThatThrownBy(()->gateway.submit(movement(event(As400SyncState.PENDING))))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void failedReadBackDoesNotAssumeSuccess() {
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class)))
                .thenReturn(List.of()).thenThrow(new DataAccessResourceFailureException("read unavailable"));
        when(jdbc.update(anyString(),any(Object[].class))).thenThrow(new DataAccessResourceFailureException("lost response"));
        assertThatThrownBy(()->gateway.submit(movement(event(As400SyncState.PENDING))))
                .isInstanceOf(DataAccessResourceFailureException.class).hasMessage("lost response");
    }

    @Test
    void ambiguousCorrelationFailsWithoutInsert() {
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of(0,0));
        assertThatThrownBy(()->gateway.submit(movement(event(As400SyncState.PENDING)))).hasMessage("AS400_MOVEMENT_AMBIGUOUS");
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test
    void submittedPollReturnsCurrentSiprov() {
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of(904736));
        assertThat(gateway.provisional(event(As400SyncState.SUBMITTED))).contains(904736);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test
    void absentProvisionalDoesNotSettle() {
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        assertThat(gateway.settled(event(As400SyncState.ACCEPTED))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings={" ","","N"})
    void onlyEtpro1OCanSettle(String etpro1) {
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of(etpro1));
        assertThat(gateway.settled(event(As400SyncState.ACCEPTED))).isFalse();
    }

    @Test
    void definitiveProvisionalSettlesWithFullClientCorrelation() {
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of("O"));
        assertThat(gateway.settled(event(As400SyncState.ACCEPTED))).isTrue();
        verify(jdbc).query(eq("SELECT ETPRO1 FROM GESCOMF.PROVISP1 WHERE STE=? AND NUPROV=? AND NUCLI=?\n"),
                any(RowMapper.class),eq("i"),eq(904736),eq(123456));
    }

    @Test
    void etpro2OIsIgnoredWhenEtpro1IsBlank() throws Exception {
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenAnswer(invocation->{
            String sql=invocation.getArgument(0);
            assertThat(sql).doesNotContain("ETPRO2");
            var rs=mock(java.sql.ResultSet.class);
            when(rs.getString(1)).thenReturn(" ");
            when(rs.getString("ETPRO2")).thenReturn("O");
            RowMapper<String> mapper=invocation.getArgument(1);
            return List.of(mapper.mapRow(rs,0));
        });
        assertThat(gateway.settled(event(As400SyncState.ACCEPTED))).isFalse();
    }

    @Test
    void lostCommitResponseIsVerifiedInANewReadCommittedDb2Transaction() throws Exception {
        var dataSource=mock(javax.sql.DataSource.class);
        var connection=mock(java.sql.Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(java.sql.Connection.TRANSACTION_READ_UNCOMMITTED);
        doThrow(new java.sql.SQLException("commit response lost")).doNothing().when(connection).commit();
        var transactionalJdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(transactionalJdbc.getDataSource()).thenReturn(dataSource);
        when(transactionalJdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of(),List.of(0));
        when(transactionalJdbc.update(anyString(),any(Object[].class))).thenReturn(1);
        var configured=new As400Configuration().as400MovementGateway(transactionalJdbc);

        assertThat(configured.submit(movement(event(As400SyncState.PENDING)))).isZero();

        verify(connection,times(2)).setTransactionIsolation(java.sql.Connection.TRANSACTION_READ_COMMITTED);
        verify(connection,times(2)).setAutoCommit(false);
        verify(connection,times(2)).commit();
        verify(transactionalJdbc,times(1)).update(anyString(),any(Object[].class));
        verify(transactionalJdbc,times(2)).query(anyString(),any(RowMapper.class),any(Object[].class));
    }
}
