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

    /** Fige le compte client du groupe dans la transaction du ledger.
     * @param orderId ordre dénoué
     */
    public void enqueueFilled(long orderId) {
        jdbc.update("""
                INSERT INTO trading_as400_sync_outbox(order_id,target,workflow_version,expected_leg_count,as400_ste,nucli_trading)
                SELECT o.id,'SICOUVI',2,4,a.as400_ste,a.as400_nucli_trading
                FROM trading_order o JOIN trading_account a ON a.id=o.account_id
                WHERE o.id=? AND o.trading_mode='LIVE'
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
                  SELECT e.id FROM trading_as400_sync_outbox e
                  JOIN trading_order o ON o.id=e.order_id AND o.trading_mode='LIVE'
                  WHERE e.target='SICOUVI' AND e.workflow_version=2 AND e.status IN ('PENDING','RETRY','PROCESSING')
                    AND e.sync_state IN ('PENDING','SUBMITTED','ACCEPTED')
                    AND e.next_attempt_at<=NOW()
                  ORDER BY e.id LIMIT ? FOR UPDATE OF e SKIP LOCKED
                )
                UPDATE trading_as400_sync_outbox o
                SET status='PROCESSING',claim_token=?,next_attempt_at=NOW()+(? * INTERVAL '1 second')
                FROM due WHERE o.id=due.id
                RETURNING o.id,o.order_id,o.target,o.attempt_count,o.claim_token,o.sync_state,
                          o.as400_ste,o.nucli_trading
                """, (rs,n) -> new As400SyncEvent(rs.getLong(1),rs.getLong(2),
                        As400SyncTarget.valueOf(rs.getString(3)),rs.getInt(4),rs.getObject(5,UUID.class),
                        As400SyncState.valueOf(rs.getString(6)),rs.getString(7),(Integer)rs.getObject(8)), limit, claimToken, lease.toSeconds());
    }

    /** Empêche un reclaim de doubler un appel encore actif ; le verrou ne touche que l'outbox.
     * Le token est revérifié après l'attente éventuelle du verrou. Aucun appel fournisseur de trading.
     * @param event claim à vérifier
     * @param action traitement AS400 isolé de la transaction FILLED
     */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
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
     * @param delay délai de contrôle
     * @throws IllegalArgumentException si la progression demandée est invalide
     */
    public void advance(As400SyncEvent event, As400SyncState state, Duration delay) {
        if (state != As400SyncState.SUBMITTED && state != As400SyncState.ACCEPTED && state != As400SyncState.SETTLED)
            throw new IllegalArgumentException("Invalid AS400 progress");
        String status=state==As400SyncState.SETTLED?"SYNCED":"PENDING";
        jdbc.update("""
                UPDATE trading_as400_sync_outbox SET status=?,sync_state=?,
                  submitted_at=COALESCE(submitted_at,NOW()),
                  accepted_at=CASE WHEN ? IN ('ACCEPTED','SETTLED') THEN COALESCE(accepted_at,NOW()) ELSE accepted_at END,
                  settled_at=CASE WHEN ?='SETTLED' THEN COALESCE(settled_at,NOW()) ELSE settled_at END,
                  synced_at=CASE WHEN ?='SETTLED' THEN COALESCE(synced_at,NOW()) ELSE synced_at END,
                  last_checked_at=NOW(),next_attempt_at=NOW()+(? * INTERVAL '1 second'),
                  last_error=NULL,claim_token=NULL
                WHERE id=? AND status='PROCESSING' AND claim_token=?
                  AND expected_leg_count=(SELECT COUNT(*) FROM trading_as400_movement m WHERE m.event_id=trading_as400_sync_outbox.id)
                  AND (SELECT MIN(CASE m.sync_state WHEN 'SUBMITTED' THEN 1 WHEN 'ACCEPTED' THEN 2
                       WHEN 'SETTLED' THEN 3 ELSE 0 END) FROM trading_as400_movement m
                       WHERE m.event_id=trading_as400_sync_outbox.id)=?
                """,status,state.name(),state.name(),state.name(),state.name(),delay.toSeconds(),event.id(),event.claimToken(),state.ordinal());
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

    /** Recharge les jambes figees ; aucun prix ni SICOUI ne vient du fournisseur au retry.
     * @param event claim courant
     * @return groupe persiste, eventuellement non prepare
     */
    As400MovementGroup group(As400SyncEvent event) {
        int count=jdbc.queryForObject("SELECT expected_leg_count FROM trading_as400_sync_outbox WHERE id=?",Integer.class,event.id());
        var legs=jdbc.query("""
                SELECT * FROM trading_as400_movement WHERE event_id=? ORDER BY leg_index
                """,(rs,n)->new As400Movement(rs.getLong("id"),rs.getInt("leg_index"),rs.getString("leg_role"),
                new SicouviMovement(rs.getString("siste"),rs.getInt("sicoui"),rs.getInt("nucli"),
                    rs.getInt("execution_date"),rs.getInt("execution_time"),rs.getString("siacfv"),
                    rs.getString("simet"),rs.getString("sicnd"),rs.getBigDecimal("sipds"),
                    rs.getBigDecimal("sitxch"),rs.getBigDecimal("sicot"),rs.getString("siref2"),rs.getString("siref3")),
                As400SyncState.valueOf(rs.getString("sync_state")),(Integer)rs.getObject("siprov")),event.id());
        return new As400MovementGroup(count,legs);
    }

    java.math.BigDecimal frozenFx(As400SyncEvent event) {
        return jdbc.queryForObject("SELECT fx_rate FROM trading_as400_sync_outbox WHERE id=?",java.math.BigDecimal.class,event.id());
    }

    void freezeFx(As400SyncEvent event, java.math.BigDecimal rate, String pair, String source, String exid) {
        jdbc.update("""
                UPDATE trading_as400_sync_outbox SET fx_rate=?,fx_frozen_at=NOW(),fx_pair=?,fx_source=?,stonex_exid=?
                WHERE id=? AND status='PROCESSING' AND claim_token=? AND fx_rate IS NULL
                """,rate,pair,source,exid,event.id(),event.claimToken());
    }

    List<Integer> allocateIdentities(int count) {
        var identities=jdbc.queryForList("SELECT nextval('trading_as400_sicoui_seq') FROM generate_series(1,?)",Integer.class,count);
        if (identities.stream().anyMatch(id->id>=800000))
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("AS400 SICOUI sequence above 80 percent before cycle");
        return identities;
    }

    void savePrepared(As400SyncEvent event, List<SicouviMovement> movements) {
        String[] roles={"CLIENT","INTERCO_LFMP","INTERCO_SAAMP","STONEX"};
        for (int i=0;i<movements.size();i++) {
            var m=movements.get(i);
            jdbc.update("""
                    INSERT INTO trading_as400_movement
                    (event_id,leg_index,leg_role,siste,nucli,sicoui,siacfv,simet,sipds,sicot,sitxch,siref3,
                     sicnd,siref2,execution_date,execution_time)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,event.id(),i,roles[i],m.ste(),m.nucli(),m.sicoui(),m.side(),m.metal(),m.grams(),
                    m.quotation(),m.fx(),m.ref3(),m.condition(),m.ref2(),m.executionDate(),m.executionTime());
        }
    }

    void saveProgress(As400SyncEvent event, As400MovementGroup group) {
        group.validate();
        for (var leg:group.legs()) {
            String state=leg.state().name();
            jdbc.update("""
                    UPDATE trading_as400_movement SET sync_state=?,siprov=?,updated_at=NOW(),last_error=NULL,
                      submitted_at=COALESCE(submitted_at,NOW()),
                      accepted_at=CASE WHEN ? IN ('ACCEPTED','SETTLED') THEN COALESCE(accepted_at,NOW()) ELSE accepted_at END,
                      settled_at=CASE WHEN ?='SETTLED' THEN COALESCE(settled_at,NOW()) ELSE settled_at END
                    WHERE id=? AND event_id=?
                    """,state,leg.siprov(),state,state,leg.id(),event.id());
        }
    }

}
