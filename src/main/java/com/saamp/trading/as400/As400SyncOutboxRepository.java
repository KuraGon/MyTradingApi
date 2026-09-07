package com.saamp.trading.as400;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Persiste les besoins AS400 sans coupler leur traitement au passage d'ordre. */
@Repository
public class As400SyncOutboxRepository {
    private final JdbcTemplate jdbc;

    /** Utilise exclusivement PostgreSQL pour la durabilité de l'outbox.
     * @param jdbc accès PostgreSQL
     */
    public As400SyncOutboxRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Fige l'identité du mouvement client dans la transaction du ledger.
     * @param orderId ordre dénoué
     */
    public void enqueueFilled(long orderId) {
        jdbc.update("""
                INSERT INTO trading_as400_sync_outbox(order_id,target,sicoui,as400_ste,nucli_trading)
                SELECT o.id,'SICOUVI',nextval('trading_as400_sicoui_seq'),a.as400_ste,a.as400_nucli_trading
                FROM trading_order o JOIN trading_account a ON a.id=o.account_id WHERE o.id=?
                ON CONFLICT (order_id,target) DO NOTHING
                """, orderId);
    }

    /** Réserve atomiquement les événements dus et récupère les baux abandonnés.
     * @param limit taille maximale
     * @param lease durée du bail
     * @return événements réclamés
     */
    public List<As400SyncEvent> claimDue(int limit, Duration lease) {
        UUID claimToken = UUID.randomUUID();
        return jdbc.query("""
                WITH due AS (
                  SELECT id FROM trading_as400_sync_outbox
                  WHERE target='SICOUVI' AND status IN ('PENDING','RETRY','PROCESSING')
                    AND sync_state IN ('PENDING','SUBMITTED','ACCEPTED')
                    AND next_attempt_at<=NOW()
                  ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED
                )
                UPDATE trading_as400_sync_outbox o
                SET status='PROCESSING',claim_token=?,next_attempt_at=NOW()+(? * INTERVAL '1 second')
                FROM due WHERE o.id=due.id
                RETURNING o.id,o.order_id,o.target,o.attempt_count,o.claim_token,o.sync_state,
                          o.sicoui,o.siprov,o.as400_ste,o.nucli_trading
                """, (rs,n) -> new As400SyncEvent(rs.getLong(1),rs.getLong(2),
                        As400SyncTarget.valueOf(rs.getString(3)),rs.getInt(4),rs.getObject(5,UUID.class),
                        As400SyncState.valueOf(rs.getString(6)),rs.getInt(7),(Integer)rs.getObject(8),
                        rs.getString(9),(Integer)rs.getObject(10)), limit, claimToken, lease.toSeconds());
    }

    /** Empêche un reclaim de doubler un appel encore actif ; le verrou ne touche que l'outbox.
     * Le token est revérifié après l'attente éventuelle du verrou. Aucun appel fournisseur de trading.
     * @param event claim à vérifier
     * @param action traitement AS400 isolé de la transaction FILLED
     */
    @Transactional
    public void withClaim(As400SyncEvent event, Consumer<As400SyncEvent> action) {
        var owned = jdbc.queryForList("""
                SELECT id FROM trading_as400_sync_outbox
                WHERE id=? AND status='PROCESSING' AND claim_token=? FOR UPDATE
                """, Long.class, event.id(), event.claimToken());
        if (!owned.isEmpty()) action.accept(event);
    }

    /** Publie une progression monotone et programme son prochain contrôle.
     * @param event claim propriétaire
     * @param state progression confirmée
     * @param siprov numéro AS400 éventuel
     * @param delay délai de contrôle
     * @throws IllegalArgumentException si la progression demandée est invalide
     */
    public void advance(As400SyncEvent event, As400SyncState state, Integer siprov, Duration delay) {
        if (state != As400SyncState.SUBMITTED && state != As400SyncState.ACCEPTED && state != As400SyncState.SETTLED)
            throw new IllegalArgumentException("Invalid AS400 progress");
        String status=state==As400SyncState.SETTLED?"SYNCED":"PENDING";
        jdbc.update("""
                UPDATE trading_as400_sync_outbox SET status=?,sync_state=?,siprov=COALESCE(?,siprov),
                  submitted_at=COALESCE(submitted_at,NOW()),
                  accepted_at=CASE WHEN ? IN ('ACCEPTED','SETTLED') THEN COALESCE(accepted_at,NOW()) ELSE accepted_at END,
                  settled_at=CASE WHEN ?='SETTLED' THEN COALESCE(settled_at,NOW()) ELSE settled_at END,
                  synced_at=CASE WHEN ?='SETTLED' THEN COALESCE(synced_at,NOW()) ELSE synced_at END,
                  last_checked_at=NOW(),next_attempt_at=NOW()+(? * INTERVAL '1 second'),
                  last_error=NULL,claim_token=NULL
                WHERE id=? AND status='PROCESSING' AND claim_token=?
                """,status,state.name(),siprov,state.name(),state.name(),state.name(),delay.toSeconds(),event.id(),event.claimToken());
    }

    /** Conserve la progression acquise après une panne ; seules les données impossibles sont terminales.
     * @param event claim propriétaire
     * @param error code sans secret
     * @param terminal donnée non exploitable
     */
    public void markFailed(As400SyncEvent event, String error, boolean terminal) {
        int attempts=event.attemptCount()+1;
        long seconds=Math.min(3600L, 15L << Math.min(attempts-1, 8));
        String state=terminal?"FAILED":event.state().name();
        String status=terminal?"BLOCKED":"RETRY";
        jdbc.update("""
                UPDATE trading_as400_sync_outbox SET status=?,sync_state=?,attempt_count=?,
                  next_attempt_at=NOW()+(? * INTERVAL '1 second'),last_checked_at=NOW(),last_error=?,claim_token=NULL
                WHERE id=? AND status='PROCESSING' AND claim_token=?
                """,status,state,attempts,seconds,error.substring(0,Math.min(512,error.length())),event.id(),event.claimToken());
    }
}
