package com.saamp.trading.as400;

import com.saamp.trading.domain.Asset;
import com.saamp.trading.domain.OrderSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Duration;

/** Traite l'outbox après commit FILLED, sans dépendance StoneX ni mutation du ledger. */
@Service
public class As400SyncWorker {
    private static final Logger log=LoggerFactory.getLogger(As400SyncWorker.class);
    private final As400SyncOutboxRepository outbox;
    private final JdbcTemplate postgres;
    private final As400MovementGateway gateway;
    private final boolean enabled;
    private final Duration submittedDelay;
    private final Duration acceptedDelay;

    /** Configure les contrôles différés sans exiger une connexion AS400 au démarrage.
     * @param outbox stockage PostgreSQL
     * @param postgres lecture de l'exécution définitive
     * @param gateway accès AS400 facultatif
     * @param enabled activation explicite du worker
     * @param submittedDelay délai d'attribution d'un provisoire
     * @param acceptedDelay délai de contrôle du traitement nocturne
     * @throws IllegalArgumentException si un délai est nul ou négatif
     */
    public As400SyncWorker(As400SyncOutboxRepository outbox, JdbcTemplate postgres, As400MovementGateway gateway,
            @Value("${trading.as400.enabled:false}") boolean enabled,
            @Value("${trading.as400.submitted-poll-delay:5m}") Duration submittedDelay,
            @Value("${trading.as400.accepted-poll-delay:1h}") Duration acceptedDelay) {
        if (submittedDelay.isNegative() || submittedDelay.isZero() || acceptedDelay.isNegative() || acceptedDelay.isZero())
            throw new IllegalArgumentException("AS400 polling delays must be positive");
        this.outbox=outbox; this.postgres=postgres; this.gateway=gateway; this.enabled=enabled;
        this.submittedDelay=submittedDelay; this.acceptedDelay=acceptedDelay;
    }

    /** Limite la charge AS400 et isole une panne d'un événement des autres claims. */
    @Scheduled(fixedDelayString="${trading.as400.sync-delay:30s}")
    public void processDue() {
        if (!enabled) return;
        for (var event:outbox.claimDue(50,Duration.ofMinutes(5))) {
            try {
                outbox.withClaim(event,this::process);
            } catch (RuntimeException ex) {
                // Une panne PostgreSQL laisse le bail récupérable, sans toucher au FILLED.
                log.warn("AS400 claim deferred eventId={} orderId={} state={} attempt={} cause={}",
                        event.id(),event.orderId(),event.state(),event.attemptCount()+1,ex.getClass().getSimpleName());
            }
        }
    }

    private void process(As400SyncEvent event) {
        try {
            switch(event.state()) {
                case PENDING -> {
                    SicouviMovement movement=movement(event);
                    int siprov=gateway.submit(movement);
                    log.info("AS400 submitted orderId={} eventId={} sicoui={} nucli={} metal={}",
                            event.orderId(),event.id(),event.sicoui(),event.nucliTrading(),movement.metal());
                    recordProvisional(event,siprov);
                }
                case SUBMITTED -> {
                    var siprov=gateway.provisional(event);
                    if (siprov.isEmpty()) throw new IllegalStateException("AS400_SUBMITTED_MOVEMENT_NOT_FOUND");
                    recordProvisional(event,siprov.get());
                }
                case ACCEPTED -> {
                    if (gateway.settled(event)) {
                        outbox.advance(event,As400SyncState.SETTLED,event.siprov(),acceptedDelay);
                        log.info("AS400 settled orderId={} siprov={}",event.orderId(),event.siprov());
                    } else outbox.advance(event,As400SyncState.ACCEPTED,event.siprov(),acceptedDelay);
                }
                default -> throw new As400SyncDataException("AS400_STATE_INVALID");
            }
        } catch (RuntimeException ex) {
            boolean terminal=ex instanceof As400SyncDataException;
            String cause=terminal?ex.getMessage():
                    (ex instanceof IllegalStateException && ex.getMessage()!=null && ex.getMessage().startsWith("AS400_")
                            ?ex.getMessage():"AS400_TEMPORARY_"+ex.getClass().getSimpleName());
            outbox.markFailed(event,cause,terminal);
            log.warn("AS400 sync error eventId={} orderId={} state={} attempt={} cause={}",
                    event.id(),event.orderId(),event.state(),event.attemptCount()+1,cause);
        }
    }

    private void recordProvisional(As400SyncEvent event,int siprov) {
        if (siprov>0) {
            outbox.advance(event,As400SyncState.ACCEPTED,siprov,acceptedDelay);
            log.info("AS400 accepted orderId={} sicoui={} siprov={}",event.orderId(),event.sicoui(),siprov);
        } else outbox.advance(event,As400SyncState.SUBMITTED,null,submittedDelay);
    }

    private SicouviMovement movement(As400SyncEvent event) {
        var rows=postgres.query("""
                SELECT o.asset,o.side,o.quantity_oz,o.client_price,RIGHT(o.pair,3),o.executed_at
                FROM trading_order o
                WHERE o.id=? AND o.status='FILLED'
                """,(rs,n)->SicouviMovement.from(event,Asset.valueOf(rs.getString(1)),OrderSide.valueOf(rs.getString(2)),
                        rs.getBigDecimal(3),rs.getBigDecimal(4),rs.getString(5),
                        rs.getTimestamp(6)==null?null:rs.getTimestamp(6).toInstant()),event.orderId());
        if (rows.size()!=1) throw new As400SyncDataException("AS400_FILLED_EXECUTION_MISSING");
        return rows.getFirst();
    }
}
