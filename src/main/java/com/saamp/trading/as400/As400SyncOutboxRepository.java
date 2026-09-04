package com.saamp.trading.as400;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.OffsetDateTime;
import java.util.List;
import java.time.Duration;
import java.util.UUID;

/** Persiste les besoins AS400 sans coupler leur traitement au passage d'ordre. */
@Repository
public class As400SyncOutboxRepository {
    private final JdbcTemplate jdbc;
    public As400SyncOutboxRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Crée une fois chaque cible d'un ordre définitivement dénoué. @param orderId ordre dénoué */
    public void enqueueFilled(long orderId) {
        jdbc.update("INSERT INTO trading_as400_sync_outbox(order_id,target) VALUES (?,'WEIGHT_ACCOUNT') ON CONFLICT DO NOTHING", orderId);
        jdbc.update("INSERT INTO trading_as400_sync_outbox(order_id,target) VALUES (?,'ACCOUNTING') ON CONFLICT DO NOTHING", orderId);
    }

    /** Réserve atomiquement les événements dus; un bail expiré rend récupérable un traitement interrompu. @param limit taille maximale @param lease durée du bail @return événements réclamés */
    public List<As400SyncEvent> claimDue(int limit, Duration lease) {
        UUID claimToken = UUID.randomUUID();
        return jdbc.query("""
                WITH due AS (
                  SELECT id FROM trading_as400_sync_outbox
                  WHERE status IN ('PENDING','RETRY','BLOCKED','PROCESSING') AND next_attempt_at<=NOW()
                  ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED
                )
                UPDATE trading_as400_sync_outbox o
                SET status='PROCESSING',claim_token=?,next_attempt_at=NOW()+(? * INTERVAL '1 second')
                FROM due WHERE o.id=due.id
                RETURNING o.id,o.order_id,o.target,o.attempt_count,o.claim_token
                """, (rs,n) -> new As400SyncEvent(rs.getLong(1),rs.getLong(2),As400SyncTarget.valueOf(rs.getString(3)),rs.getInt(4),rs.getObject(5,UUID.class)),
                limit, claimToken, lease.toSeconds());
    }

    /** Marque le succès idempotent. @param id événement */
    public void markSynced(As400SyncEvent event) { jdbc.update("UPDATE trading_as400_sync_outbox SET status='SYNCED',synced_at=NOW(),last_error=NULL,claim_token=NULL WHERE id=? AND status='PROCESSING' AND claim_token=?",event.id(),event.claimToken()); }

    /** Conserve l'événement et planifie son rejeu. @param event événement @param error erreur interne @param blocked contrat manquant */
    public void markFailed(As400SyncEvent event, String error, boolean blocked) {
        int attempts=event.attemptCount()+1;
        long seconds=Math.min(3600L, 15L << Math.min(attempts-1, 8));
        jdbc.update("UPDATE trading_as400_sync_outbox SET status=?,attempt_count=?,next_attempt_at=?,last_error=?,claim_token=NULL WHERE id=? AND status='PROCESSING' AND claim_token=?",
                blocked?"BLOCKED":"RETRY",attempts,OffsetDateTime.now().plusSeconds(seconds),truncate(error),event.id(),event.claimToken());
    }
    private String truncate(String value) { String v=value==null?"AS400 synchronization failed":value; return v.substring(0,Math.min(512,v.length())); }
}
