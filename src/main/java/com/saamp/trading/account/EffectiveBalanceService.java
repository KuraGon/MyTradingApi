package com.saamp.trading.account;

import com.saamp.trading.common.TradingException;
import com.saamp.trading.domain.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Source unique des comptes opérationnels ; la projection locale reste réservée au settlement. */
@Service
public class EffectiveBalanceService {
    private final BalanceRepository projection;
    private final AccountRepository accounts;
    private final PendingTradingAdjustmentRepository adjustments;
    private final OfficialTradingBalanceReader official;
    private final EffectiveBalanceProperties config;
    /** @param projection historique interne @param accounts identité trading @param adjustments faits persistés
     * @param official lecture officielle @param config mode et fraîcheur technique */
    public EffectiveBalanceService(BalanceRepository projection, AccountRepository accounts,
            PendingTradingAdjustmentRepository adjustments, OfficialTradingBalanceReader official, EffectiveBalanceProperties config) {
        this.projection=projection;this.accounts=accounts;this.adjustments=adjustments;this.official=official;this.config=config;
    }
    /** @return mode imposant la source officielle pour toute décision */
    public boolean enforced() { return config.getMode()==EffectiveBalanceProperties.Mode.ENFORCED; }
    /** @return nécessité de rechercher aussi les positions existant uniquement dans AS400 */
    public boolean usesOfficial() { return config.getMode()!=EffectiveBalanceProperties.Mode.LEGACY; }
    /** @param accountId compte @return soldes selon le mode, sans fallback en ENFORCED */
    public List<Balance> findAll(long accountId) {
        if(!usesOfficial()) return projection.findAll(accountId);
        return forOperation(capture(accounts.findById(accountId).orElseThrow(EffectiveBalanceService::unavailable)));
    }
    /** @param account compte @return instantané borné acquis sans verrou PostgreSQL
     * @throws TradingException si ENFORCED et lecture non fiable
     * @throws IllegalStateException si le cutover requis est absent */
    public EffectiveBalanceSnapshot capture(TradingAccount account) {
        config.validateConfiguration();
        Instant start=Instant.now();
        if(!usesOfficial()) return snapshot(account,start,true,null,List.of(),Map.of(),Map.of(),Map.of(),List.of(),projection.findAll(account.id()),false);
        boolean enforced=config.getMode()==EffectiveBalanceProperties.Mode.ENFORCED;
        try {
            if(TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
            if(account.baseCurrency()!=Asset.EUR) throw new TradingException(HttpStatus.SERVICE_UNAVAILABLE,"OFFICIAL_CURRENCY_UNSUPPORTED","Devise officielle non validée");
            var facts=adjustments.read(account.id());
            var seen=new HashSet<Long>();
            for(var fact:facts) {
                if((fact.ste()!=null && !fact.ste().equals(account.as400Ste()))
                        || (fact.nucli()!=null && !fact.nucli().equals(account.as400NucliTrading()))
                        || !seen.add(fact.orderId()) || fact.currency()!=Asset.EUR || !fact.metal().isMetal()
                        || fact.quantityOz()==null || fact.quantityOz().signum()<=0 || fact.grossAmount()==null || fact.grossAmount().signum()<=0)
                    throw unavailable();
            }
            // Double collecte : une imputation ou variation observée pendant l'acquisition invalide l'ensemble.
            var first=official.read(account,facts);
            var second=official.read(account,facts);
            if(!first.equals(second) || !facts.equals(adjustments.read(account.id()))
                    || !Instant.now().isBefore(start.plus(config.getMaxSnapshotAge()))) throw unavailable();
            var pending=new EnumMap<Asset,BigDecimal>(Asset.class);
            for(var fact:facts) {
                Boolean posted=second.clientPosted().get(fact.orderId());
                if(posted==null) throw unavailable();
                if(!posted) {
                    boolean buy=fact.side()==OrderSide.BUY;
                    pending.merge(fact.metal(),buy?fact.quantityOz():fact.quantityOz().negate(),BigDecimal::add);
                    pending.merge(fact.currency(),buy?fact.grossAmount().negate():fact.grossAmount(),BigDecimal::add);
                }
            }
            var result=new ArrayList<Balance>();
            for(var asset:List.of(Asset.EUR,Asset.XAU,Asset.XAG,Asset.XPT,Asset.XPD)) {
                var value=second.balances().get(asset);
                if(value==null) throw unavailable();
                result.add(new Balance(account.id(),asset,value.add(pending.getOrDefault(asset,BigDecimal.ZERO)),OffsetDateTime.now(ZoneOffset.UTC)));
            }
            if(!enforced) org.slf4j.LoggerFactory.getLogger(getClass()).info("Effective balance SHADOW accountId={} available=true",account.id());
            return snapshot(account,start,true,null,facts,second.balances(),pending,second.clientPosted(),result,
                    enforced?result:projection.findAll(account.id()),enforced);
        } catch(RuntimeException failure) {
            if(enforced) {
                if(failure instanceof TradingException trading) throw trading;
                throw unavailable();
            }
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Effective balance SHADOW accountId={} available=false",account.id());
            return snapshot(account,start,false,"OFFICIAL_BALANCE_UNAVAILABLE",List.of(),Map.of(),Map.of(),Map.of(),List.of(),projection.findAll(account.id()),false);
        }
    }
    /** @param snapshot lecture effectuée @return données opposables au mode courant, SHADOW restant local */
    public List<Balance> forOperation(EffectiveBalanceSnapshot snapshot) {
        return snapshot.decisionBalances();
    }
    /** Revalidation locale sous ACCOUNT : aucune connexion IBM i sous verrou.
     * @param account compte relu @param snapshot acquisition antérieure
     * @return soldes encore opposables @throws TradingException si faits ou fraîcheur modifiés */
    public List<Balance> validate(TradingAccount account,EffectiveBalanceSnapshot snapshot) {
        // En LEGACY/SHADOW, la decision locale est toujours relue sous verrou ACCOUNT.
        if(!snapshot.enforced()) return projection.findAll(account.id());
        if(snapshot.accountId()!=account.id() || !Objects.equals(snapshot.ste(),account.as400Ste())
                || !Objects.equals(snapshot.nucliTrading(),account.as400NucliTrading()) || account.baseCurrency()!=Asset.EUR
                || !snapshot.available() || !Instant.now().isBefore(snapshot.startedAt().plus(config.getMaxSnapshotAge()))
                || !snapshot.facts().equals(adjustments.read(account.id()))) throw unavailable();
        return snapshot.decisionBalances();
    }
    private EffectiveBalanceSnapshot snapshot(TradingAccount account,Instant start,boolean available,String error,
            List<PendingTradingAdjustmentRepository.Adjustment> facts,Map<Asset,BigDecimal> official,
            Map<Asset,BigDecimal> pending,Map<Long,Boolean> posted,List<Balance> calculated,List<Balance> decision,boolean enforced) {
        return new EffectiveBalanceSnapshot(account.id(),account.as400Ste(),account.as400NucliTrading(),official,pending,calculated,decision,
                start,Instant.now(),available,error,posted,facts,enforced);
    }
    private static TradingException unavailable() {
        return new TradingException(HttpStatus.SERVICE_UNAVAILABLE,"OFFICIAL_BALANCE_UNAVAILABLE","Compte officiel AS400 indisponible ou incohérent");
    }
}
