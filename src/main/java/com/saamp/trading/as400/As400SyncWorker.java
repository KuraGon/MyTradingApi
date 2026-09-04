package com.saamp.trading.as400;
import org.slf4j.Logger; import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.saamp.trading.domain.Asset;
import java.time.Duration;

/** Traite uniquement l'outbox AS400 et ne possède aucune dépendance fournisseur StoneX. */
@Service public class As400SyncWorker {
    private static final Logger log=LoggerFactory.getLogger(As400SyncWorker.class);
    private final As400SyncOutboxRepository outbox; private final JdbcTemplate postgres;
    private final As400WeightAccountWriter weights; private final AccountingIntegrationPort accounting;
    public As400SyncWorker(As400SyncOutboxRepository outbox,JdbcTemplate postgres,As400WeightAccountWriter weights,AccountingIntegrationPort accounting){this.outbox=outbox;this.postgres=postgres;this.weights=weights;this.accounting=accounting;}
    /** Traite les événements dus, indépendamment du statut déjà FILLED. */
    @Scheduled(fixedDelayString="${trading.as400.sync-delay:30s}") public void processDue(){
        for(var event:outbox.claimDue(50,Duration.ofMinutes(5))) try{
            if(event.target()==As400SyncTarget.ACCOUNTING) accounting.synchronize(event.orderId());
            else { var c=context(event.orderId()); if(c.ste()==null||c.nucliTrading()==null) throw new IllegalStateException("AS400 trading account mapping not configured"); weights.writeCurrentBalance(c.ste(),c.nucliTrading(),c.asset(),c.balance()); }
            outbox.markSynced(event); log.info("AS400 synchronization succeeded orderId={} target={}",event.orderId(),event.target());
        }catch(RuntimeException ex){boolean blocked=ex.getMessage()!=null&&(ex.getMessage().contains("not configured")||ex.getMessage().contains("TDMV03"));outbox.markFailed(event,ex.getMessage(),blocked);if(event.attemptCount()+1>=5)log.warn("AS400 synchronization requires attention orderId={} target={} attempts={}",event.orderId(),event.target(),event.attemptCount()+1);else log.warn("AS400 synchronization deferred orderId={} target={}",event.orderId(),event.target());}
    }
    private As400SyncContext context(long orderId){return postgres.queryForObject("""
      SELECT a.as400_ste,a.as400_nucli_trading,o.asset,COALESCE(b.quantity,0)
      FROM trading_order o JOIN trading_account a ON a.id=o.account_id
      LEFT JOIN trading_balance b ON b.account_id=o.account_id AND b.asset=o.asset WHERE o.id=?
      """,(rs,n)->new As400SyncContext(rs.getString(1),(Integer)rs.getObject(2),Asset.valueOf(rs.getString(3)),rs.getBigDecimal(4)),orderId);}
}
