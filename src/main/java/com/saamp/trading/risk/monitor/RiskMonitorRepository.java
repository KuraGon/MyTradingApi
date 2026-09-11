package com.saamp.trading.risk.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saamp.trading.account.AccountRepository;
import com.saamp.trading.risk.monitor.RiskMonitorPolicy.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.time.Duration;
import java.sql.Timestamp;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Persiste exclusivement le suivi du risque et ses livraisons PostgreSQL. */
public final class RiskMonitorRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AccountRepository accounts;
    private final ObjectMapper json;
    RiskMonitorRepository(JdbcTemplate jdbc, TransactionTemplate tx, AccountRepository accounts, ObjectMapper json) {
        this.jdbc=jdbc; this.tx=tx; this.accounts=accounts; this.json=json;
    }
    record State(long version, Level level, Gates gates, Instant calculatedAt, String error) { }
    record Event(UUID id, long accountId, long sequence, Level level, String indicators,
                 Instant priceAsOf, Instant createdAt, String reason, UUID token, int attempts) { }

    List<Long> allCandidates(long after, int limit) {
        return jdbc.queryForList("SELECT id FROM trading_account WHERE id>? ORDER BY id LIMIT ?",Long.class,after,limit);
    }
    List<Long> candidates(long after, int limit) {
        return jdbc.queryForList("""
            SELECT a.id FROM trading_account a WHERE a.id>? AND
              (EXISTS(SELECT 1 FROM trading_balance b WHERE b.account_id=a.id AND b.asset IN ('XAU','XAG','XPT','XPD') AND b.quantity<>0)
               OR EXISTS(SELECT 1 FROM trading_risk_monitor_state s WHERE s.account_id=a.id AND
                    (s.level IS DISTINCT FROM 'NO_POSITION' OR s.error_code IS NOT NULL)))
            ORDER BY a.id LIMIT ?
            """, Long.class, after, limit);
    }
    long version(long id) {
        return jdbc.query("SELECT version FROM trading_risk_monitor_state WHERE account_id=?",(r,n)->r.getLong(1),id)
                .stream().findFirst().orElse(0L);
    }
    String fingerprint(long id) {
        var parts = List.of(
            jdbc.queryForList("SELECT * FROM trading_account WHERE id=?",id),
            jdbc.queryForList("SELECT * FROM trading_balance WHERE account_id=? ORDER BY asset",id),
            jdbc.queryForList("SELECT * FROM trading_spread WHERE company_id=(SELECT company_id FROM trading_account WHERE id=?) AND active_from<=clock_timestamp() AND (active_to IS NULL OR active_to>clock_timestamp()) ORDER BY id",id),
            jdbc.queryForList("SELECT * FROM trading_margin_rate WHERE (account_id=? OR account_id IS NULL) AND active_from<=clock_timestamp() AND (active_to IS NULL OR active_to>clock_timestamp()) ORDER BY id",id),
            jdbc.queryForList("SELECT * FROM trading_asset_config ORDER BY asset"));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(parts.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("Risk monitor fingerprint failed"); }
    }
    String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Risk monitor serialization failed"); }
    }
    private State state(long id) {
        return jdbc.query("SELECT * FROM trading_risk_monitor_state WHERE account_id=? FOR UPDATE",(r,n)-> {
            try { return new State(r.getLong("version"),r.getString("level")==null?null:Level.valueOf(r.getString("level")),
                    json.readValue(r.getString("gates"),Gates.class),r.getTimestamp("calculated_at").toInstant(),r.getString("error_code")); }
            catch (Exception e) { throw new IllegalStateException("Risk monitor state invalid"); }
        },id).stream().findFirst().orElse(new State(0,null,Gates.empty(),null,null));
    }

    boolean persist(RiskMonitorService.Observation observation, RiskMonitorProperties config, java.time.Clock clock) {
        return persist(observation,config,clock,()->{});
    }
    boolean persist(RiskMonitorService.Observation observation, RiskMonitorProperties config, java.time.Clock clock,Runnable validate) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            long id=observation.accountId();
            if (accounts.lockById(id).isEmpty()) return false;
            validate.run();
            State old=state(id);
            if (old.version()!=observation.version() || !fingerprint(id).equals(observation.fingerprint())
                    || old.calculatedAt()!=null && !observation.at().isAfter(old.calculatedAt())) return false;
            Instant now=clock.instant();
            String error=observation.error();
            Level next=observation.level();
            if (next!=null && next!=Level.NO_POSITION && next!=Level.PRICE_STALE
                    && (observation.priceAsOf()==null || observation.priceAsOf().isAfter(now)
                        || observation.priceAsOf().plus(config.maxPriceAge()).isBefore(now))) {
                next=Level.PRICE_STALE; error="MARKET_PRICE_STALE";
            }
            Decision decision;
            if (next==null) decision=new Decision(old.level(),new Gates(old.gates().mask(),Arrays.asList(null,null,null)),false);
            else decision=RiskMonitorPolicy.advance(old.level(),old.gates(),old.error()==null?old.calculatedAt():null,
                    next,observation.coverage(),now,config);
            boolean valid=next!=null && next!=Level.PRICE_STALE;
            String indicators=valid ? observation.indicators() : null;
            long sequence=old.version()+1;
            jdbc.update("""
                INSERT INTO trading_risk_monitor_state(account_id,version,level,indicators,price_as_of,calculated_at,valid_calculated_at,gates,error_code)
                VALUES (?,?,?,?::jsonb,?,?,?,?::jsonb,?)
                ON CONFLICT(account_id) DO UPDATE SET version=EXCLUDED.version,level=EXCLUDED.level,
                  indicators=COALESCE(EXCLUDED.indicators,trading_risk_monitor_state.indicators),
                  price_as_of=CASE WHEN EXCLUDED.valid_calculated_at IS NOT NULL THEN EXCLUDED.price_as_of ELSE trading_risk_monitor_state.price_as_of END,
                  calculated_at=EXCLUDED.calculated_at,valid_calculated_at=COALESCE(EXCLUDED.valid_calculated_at,trading_risk_monitor_state.valid_calculated_at),
                  gates=EXCLUDED.gates,error_code=EXCLUDED.error_code
                """,id,sequence,decision.level()==null?null:decision.level().name(),indicators,
                    timestamp(valid?observation.priceAsOf():null),timestamp(observation.at()),timestamp(valid?observation.at():null),encode(decision.gates()),error);
            if (decision.notifyEmail() || old.level()!=decision.level() || !Objects.equals(old.error(),error)) {
                jdbc.update("""
                    INSERT INTO trading_risk_monitor_event(id,account_id,transition_sequence,old_level,new_level,indicators,price_as_of,created_at,reason,delivery_status,next_attempt_at)
                    VALUES (?,?,?,?,?,?::jsonb,?,?,?,?,?)
                    """,UUID.randomUUID(),id,sequence,old.level()==null?null:old.level().name(),decision.level()==null?null:decision.level().name(),
                        indicators,timestamp(observation.priceAsOf()),timestamp(now),error,
                        decision.notifyEmail()?"PENDING":"AUDIT",timestamp(now));
            }
            return true;
        }));
    }

    List<Event> claim(int limit, Duration lease, int maxAttempts) {
        return tx.execute(status -> {
            jdbc.update("""
                WITH exhausted AS (
                  SELECT id FROM trading_risk_monitor_event WHERE attempt_count>=? AND
                    (delivery_status IN ('PENDING','RETRY') OR (delivery_status='SENDING' AND lease_until<=clock_timestamp()))
                  ORDER BY next_attempt_at,created_at,id LIMIT ? FOR UPDATE SKIP LOCKED
                ) UPDATE trading_risk_monitor_event e SET delivery_status='FAILED',claim_token=NULL,lease_until=NULL,
                    next_attempt_at=NULL,last_error='SMTP_ATTEMPTS_EXHAUSTED'
                  FROM exhausted WHERE e.id=exhausted.id
                """,maxAttempts,limit);
            return jdbc.query("""
            WITH due AS (
              SELECT id FROM trading_risk_monitor_event WHERE attempt_count<? AND (
                (delivery_status IN ('PENDING','RETRY') AND next_attempt_at<=clock_timestamp())
                OR (delivery_status='SENDING' AND lease_until<=clock_timestamp()))
              ORDER BY next_attempt_at,created_at,id LIMIT ? FOR UPDATE SKIP LOCKED
            ) UPDATE trading_risk_monitor_event e SET delivery_status='SENDING',claim_token=?,
              lease_until=clock_timestamp()+(? * interval '1 millisecond'),attempt_count=attempt_count+1
              FROM due WHERE e.id=due.id RETURNING e.*
            """,(r,n)->new Event(r.getObject("id",UUID.class),r.getLong("account_id"),r.getLong("transition_sequence"),
                    Level.valueOf(r.getString("new_level")),r.getString("indicators"),
                    r.getTimestamp("price_as_of")==null?null:r.getTimestamp("price_as_of").toInstant(),
                    r.getTimestamp("created_at").toInstant(),r.getString("reason"),r.getObject("claim_token",UUID.class),r.getInt("attempt_count")),
                    maxAttempts,limit,UUID.randomUUID(),lease.toMillis());
        });
    }
    /** Renouvelle uniquement un bail encore valide dont le worker possède toujours le token. */
    boolean renew(Event event, Duration lease) {
        return Boolean.TRUE.equals(tx.execute(status -> jdbc.update("""
            UPDATE trading_risk_monitor_event SET lease_until=clock_timestamp()+(? * interval '1 millisecond')
            WHERE id=? AND claim_token=? AND delivery_status='SENDING' AND lease_until>clock_timestamp()
            """,lease.toMillis(),event.id(),event.token())==1));
    }
    boolean acknowledge(Event event) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            int count=jdbc.update("""
                UPDATE trading_risk_monitor_event SET delivery_status='SENT',sent_at=clock_timestamp(),claim_token=NULL,lease_until=NULL,last_error=NULL
                WHERE id=? AND claim_token=? AND delivery_status='SENDING' AND lease_until>clock_timestamp()
                """,event.id(),event.token());
            if (count==1) jdbc.update("""
                UPDATE trading_risk_monitor_state SET last_notified_level=?,last_alert_at=clock_timestamp(),last_notified_sequence=?
                WHERE account_id=? AND last_notified_sequence<?
                """,event.level().name(),event.sequence(),event.accountId(),event.sequence());
            return count==1;
        }));
    }
    void retry(Event event, Duration delay, int maxAttempts) {
        tx.executeWithoutResult(status -> jdbc.update("""
            UPDATE trading_risk_monitor_event SET delivery_status=CASE WHEN attempt_count>=? THEN 'FAILED' ELSE 'RETRY' END,
              next_attempt_at=CASE WHEN attempt_count>=? THEN NULL ELSE clock_timestamp()+(? * interval '1 millisecond') END,
              claim_token=NULL,lease_until=NULL,last_error=CASE WHEN attempt_count>=? THEN 'SMTP_ATTEMPTS_EXHAUSTED' ELSE 'SMTP_SEND_FAILED' END
            WHERE id=? AND claim_token=? AND delivery_status='SENDING' AND lease_until>clock_timestamp()
            """,maxAttempts,maxAttempts,delay.toMillis(),maxAttempts,event.id(),event.token()));
    }
    private static Timestamp timestamp(Instant value) { return value==null?null:Timestamp.from(value); }
}
