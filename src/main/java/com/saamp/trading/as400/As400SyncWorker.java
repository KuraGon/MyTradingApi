package com.saamp.trading.as400;

import com.saamp.trading.domain.Asset;
import com.saamp.trading.domain.OrderSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Prepare puis traite le groupe hors transaction FILLED, sans mutation du ledger. */
@Service
public class As400SyncWorker {
    private static final Logger log=LoggerFactory.getLogger(As400SyncWorker.class);
    private final As400SyncOutboxRepository outbox;
    private final JdbcTemplate postgres;
    private final As400MovementGateway gateway;
    private final As400TradingMapping mapping;
    private final As400FxSource fxSource;
    private final boolean enabled;
    private final Duration submittedDelay;
    private final Duration acceptedDelay;

    /** Configure les controles differes sans exiger de connexion AS400 au demarrage.
     * @param outbox stockage PostgreSQL
     * @param postgres lecture de l'execution definitive
     * @param gateway acces AS400 facultatif
     * @param mapping comptes comptables controles
     * @param fxSource lecture EURUSD sans ordre fournisseur
     * @param enabled activation explicite
     * @param submittedDelay delai d'attribution des provisoires
     * @param acceptedDelay delai du traitement nocturne
     * @throws IllegalArgumentException si un delai n'est pas positif
     */
    public As400SyncWorker(As400SyncOutboxRepository outbox, JdbcTemplate postgres, As400MovementGateway gateway,
            As400TradingMapping mapping, As400FxSource fxSource,
            @Value("${trading.as400.enabled:false}") boolean enabled,
            @Value("${trading.as400.submitted-poll-delay:5m}") Duration submittedDelay,
            @Value("${trading.as400.accepted-poll-delay:1h}") Duration acceptedDelay) {
        if (submittedDelay.isNegative() || submittedDelay.isZero() || acceptedDelay.isNegative() || acceptedDelay.isZero())
            throw new IllegalArgumentException("AS400 polling delays must be positive");
        this.outbox=outbox; this.postgres=postgres; this.gateway=gateway; this.mapping=mapping; this.fxSource=fxSource;
        this.enabled=enabled; this.submittedDelay=submittedDelay; this.acceptedDelay=acceptedDelay;
    }

    /** Commit la preparation avant DB2, puis reverifie le token sous verrou pour l'envoi. */
    @Scheduled(fixedDelayString="${trading.as400.sync-delay:30s}")
    public void processDue() {
        if (!enabled) return;
        for (var event:outbox.claimDue(50,Duration.ofMinutes(5))) {
            try {
                if (event.state()==As400SyncState.PENDING)
                    outbox.withClaim(event,e -> guarded(e,() -> prepare(e)));
                // withClaim termine sa transaction PostgreSQL avant ce second appel.
                outbox.withClaim(event,e -> guarded(e,() -> process(e)));
            } catch (RuntimeException ex) {
                log.warn("AS400 claim deferred eventId={} orderId={} state={} attempt={} cause={}",
                        event.id(),event.orderId(),event.state(),event.attemptCount()+1,ex.getClass().getSimpleName());
            }
        }
    }

    private void guarded(As400SyncEvent event, Runnable action) {
        try {
            action.run();
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

    private void prepare(As400SyncEvent event) {
        var group=outbox.group(event);
        if (!group.legs().isEmpty()) {
            group.validate();
            return;
        }
        if (group.expectedLegCount()!=4) throw new As400SyncDataException("AS400_GROUP_INCOMPLETE");
        var execution=execution(event);
        execution.validate();
        var fx=outbox.frozenFx(event);
        if (fx==null) {
            var frozen="EUR".equals(execution.currency())
                    ?new As400FxSource.FrozenFx(new BigDecimal("1.00"),"EUR_PARITY"):fxSource.fetch();
            fx=frozen.rate();
            // Valider tout avant de figer et persister ; aucun mouvement partiel en PostgreSQL.
            execution.build(event,mapping,fx,List.of(1,2,3,4));
            outbox.freezeFx(event,fx,"USD".equals(execution.currency())?"EURUSD":null,frozen.source(),execution.exid());
        }
        var movements=execution.build(event,mapping,fx,outbox.allocateIdentities(group.expectedLegCount()));
        outbox.savePrepared(event,movements);
    }

    private void process(As400SyncEvent event) {
        var group=outbox.group(event);
        group.validate();
        var legs=new ArrayList<As400Movement>();
        if (event.state()==As400SyncState.PENDING || event.state()==As400SyncState.SUBMITTED) {
            var provisionals=event.state()==As400SyncState.PENDING?gateway.submit(group):gateway.provisional(group);
            if (provisionals.size()!=group.expectedLegCount())
                throw new As400SyncDataException("AS400_GROUP_INCOMPLETE");
            for (int i=0;i<group.legs().size();i++)
                legs.add(group.legs().get(i).progress(provisionals.get(i),false));
        } else if (event.state()==As400SyncState.ACCEPTED) {
            for (var leg:group.legs()) {
                if (leg.siprov()==null) throw new As400SyncDataException("AS400_SIPROV_MISSING");
                legs.add(leg.progress(leg.siprov(),leg.state()==As400SyncState.SETTLED || gateway.settled(leg)));
            }
        } else throw new As400SyncDataException("AS400_STATE_INVALID");
        var progress=new As400MovementGroup(group.expectedLegCount(),legs);
        outbox.saveProgress(event,progress);
        var state=progress.state();
        outbox.advance(event,state,state==As400SyncState.SUBMITTED?submittedDelay:acceptedDelay);
        for (var leg:legs) log.info("AS400 group progress eventId={} orderId={} role={} state={} sicoui={} siprov={} nucli={} metal={}",
                event.id(),event.orderId(),leg.legRole(),leg.state(),leg.data().sicoui(),leg.siprov(),leg.data().nucli(),leg.data().metal());
    }

    private As400GroupPreparation execution(As400SyncEvent event) {
        var rows=postgres.query("""
                SELECT asset,side,quantity_oz,client_price,market_price,RIGHT(pair,3),executed_at,stonex_exid
                FROM trading_order WHERE id=? AND status='FILLED'
                """,(rs,n)->new As400GroupPreparation(Asset.valueOf(rs.getString(1)),OrderSide.valueOf(rs.getString(2)),
                        rs.getBigDecimal(3),rs.getBigDecimal(4),rs.getBigDecimal(5),rs.getString(6),
                        rs.getTimestamp(7)==null?null:rs.getTimestamp(7).toInstant(),rs.getString(8)),event.orderId());
        if (rows.size()!=1) throw new As400SyncDataException("AS400_FILLED_EXECUTION_MISSING");
        return rows.getFirst();
    }
}
