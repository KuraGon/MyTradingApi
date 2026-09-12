package com.saamp.trading.as400;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static com.saamp.trading.as400.As400SyncFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Protège la séparation entre planification technique et progression métier durable. */
class As400SyncOutboxRepositoryTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final As400SyncOutboxRepository outbox=new As400SyncOutboxRepository(jdbc);

    @ParameterizedTest
    @CsvSource({"SUBMITTED,PENDING","ACCEPTED,PENDING","SETTLED,SYNCED"})
    void confirmedProgressReleasesClaimWithTechnicalStatus(As400SyncState state,String status) {
        var event=event(As400SyncState.PENDING);
        Integer siprov=state==As400SyncState.SUBMITTED?null:904736;
        outbox.advance(event,state,Duration.ofMinutes(5));

        var sql=ArgumentCaptor.forClass(String.class);
        var args=ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(),args.capture());
        assertThat(args.getValue()).containsExactly(status,state.name(),state.name(),state.name(),
                state.name(),300L,event.id(),event.claimToken(),state.ordinal());
        assertThat(sql.getValue()).contains("status=?,sync_state=?",
                "synced_at=CASE WHEN ?='SETTLED'", "claim_token=NULL",
                "WHERE id=? AND status='PROCESSING' AND claim_token=?");
        assertThat(sql.getValue().chars().filter(c->c=='?').count()).isEqualTo(args.getValue().length);
    }

    @ParameterizedTest
    @EnumSource(value=As400SyncState.class,names={"PENDING","SUBMITTED","ACCEPTED"})
    void transientFailureUsesRetryAndPreservesBusinessProgress(As400SyncState state) {
        var event=event(state);
        outbox.markFailed(event,"AS400_TEMPORARY_TEST",false);

        var args=ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(),args.capture());
        assertThat(args.getValue()).containsExactly("RETRY",state.name(),1,15L,
                "AS400_TEMPORARY_TEST",event.id(),event.claimToken());
    }

    @Test
    void impossibleDataBlocksTechnicalDeliveryAndFailsBusinessSync() {
        var event=event(As400SyncState.PENDING);
        outbox.markFailed(event,"AS400_WEIGHT_ROUNDS_TO_ZERO",true);

        var args=ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(),args.capture());
        assertThat(args.getValue()).containsExactly("BLOCKED","FAILED",1,15L,
                "AS400_WEIGHT_ROUNDS_TO_ZERO",event.id(),event.claimToken());
    }

    @Test
    void claimExcludesBlockedLegacyAndTerminalProgressWithoutChangingSyncState() {
        outbox.claimDue(50,Duration.ofMinutes(5));
        var sql=ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(),any(RowMapper.class),any(Object[].class));
        assertThat(sql.getValue()).contains("target='SICOUVI'",
                "status IN ('PENDING','RETRY','PROCESSING')",
                "sync_state IN ('PENDING','SUBMITTED','ACCEPTED')",
                "FOR UPDATE OF e SKIP LOCKED");
        String mutations=sql.getValue().substring(sql.getValue().indexOf("SET status="),
                sql.getValue().indexOf("FROM due"));
        assertThat(mutations).contains("status='PROCESSING'","claim_token=?").doesNotContain("sync_state");
    }
}
