package com.saamp.trading.account;

import com.saamp.trading.common.TradingException;
import com.saamp.trading.domain.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;

/** Source unique des comptes opérationnels ; la projection locale reste réservée au settlement. */
@Service
public class EffectiveBalanceService {
    private final BalanceRepository projection;
    private final DemoBalanceRepository demoProjection;
    private final AccountRepository accounts;
    private final PendingTradingAdjustmentRepository adjustments;
    private final OfficialTradingBalanceReader official;
    private final EffectiveBalanceProperties config;
    private final ShadowBalanceDiagnostics shadow = new ShadowBalanceDiagnostics();
    private final EffectiveBalanceTelemetry telemetry;
    /** @param projection historique interne @param accounts identité trading @param adjustments faits persistés
     * @param official lecture officielle @param config mode et fraîcheur technique */
    public EffectiveBalanceService(BalanceRepository projection, AccountRepository accounts,
            PendingTradingAdjustmentRepository adjustments, OfficialTradingBalanceReader official, EffectiveBalanceProperties config) {
        this(projection, accounts, adjustments, official, config, null, new SimpleMeterRegistry());
    }
    /** @param projection historique interne @param accounts identite trading @param adjustments faits persistes
     * @param official lecture officielle @param config mode @param metrics mesures techniques sans soldes */
    public EffectiveBalanceService(BalanceRepository projection, AccountRepository accounts,
            PendingTradingAdjustmentRepository adjustments, OfficialTradingBalanceReader official,
            EffectiveBalanceProperties config, MeterRegistry metrics) {
        this(projection, accounts, adjustments, official, config, null, metrics);
    }
    /** Spring constructor; DEMO projection is deliberately independent from the AS400-aware LIVE projection. */
    @Autowired
    public EffectiveBalanceService(BalanceRepository projection, AccountRepository accounts,
            PendingTradingAdjustmentRepository adjustments, OfficialTradingBalanceReader official,
            EffectiveBalanceProperties config, DemoBalanceRepository demoProjection, MeterRegistry metrics) {
        this.projection=projection;this.demoProjection=demoProjection;this.accounts=accounts;this.adjustments=adjustments;this.official=official;this.config=config;
        this.telemetry = new EffectiveBalanceTelemetry(metrics);
    }
    /** @return mode imposant la source officielle pour toute décision */
    public boolean enforced() { return config.getMode()==EffectiveBalanceProperties.Mode.ENFORCED; }
    /** @return nécessité de rechercher aussi les positions existant uniquement dans AS400 */
    public boolean usesOfficial() { return config.getMode()!=EffectiveBalanceProperties.Mode.LEGACY; }
    /** @param accountId compte @return soldes selon le mode, sans fallback en ENFORCED */
    public List<Balance> findAll(long accountId) {

        var account=accounts.findById(accountId).orElseThrow(EffectiveBalanceService::unavailable);
        return forOperation(captureForOperation(account));
    }
    /** Resolves a DEMO request from the dedicated local projection, independently from EffectiveBalance mode. */
    public List<Balance> findAll(long accountId, TradingMode tradingMode) {
        var account=accounts.findById(accountId).orElseThrow(EffectiveBalanceService::unavailable);
        AccountService.requireMode(account,tradingMode);
        if (tradingMode == TradingMode.DEMO) return demoProjection().findAll(accountId);
        return findAll(accountId);
    }
    /** @param account compte @return instantané borné acquis sans verrou PostgreSQL
     * @throws TradingException si ENFORCED et lecture non fiable
     * @throws IllegalStateException si le cutover requis est absent */
    public EffectiveBalanceSnapshot capture(TradingAccount account) {
        return captureDiagnostic(account);
    }

    /**
     * Acquiert la décision immédiatement opposable. En SHADOW, la projection
     * PostgreSQL est retournée sans attendre DB2, tandis que le diagnostic
     * complet est lancé hors du chemin utilisateur. Les autres modes gardent
     * l'acquisition synchrone imposée par leur contrat.
     */
    public EffectiveBalanceSnapshot captureForOperation(TradingAccount account) {
        if (account.accountMode()==TradingMode.DEMO) return captureForOperation(account,TradingMode.DEMO);
        config.validateConfiguration();
        if (config.getMode() != EffectiveBalanceProperties.Mode.SHADOW) return captureDiagnostic(account);
        Instant start = Instant.now();
        var legacy = projection.findAll(account.id());
        // Aucune attente reseau, aucune reutilisation d'un resultat diagnostique pour une decision.
        String reason = shadow.submit(() -> captureDiagnostic(account).available());
        if (!"DIAGNOSTIC_PENDING".equals(reason)) telemetry.skipped(reason);
        return snapshot(account,start,false,reason,List.of(),Map.of(),Map.of(),Map.of(),List.of(),legacy,false);
    }
    /**
     * DEMO deliberately bypasses every official reader and every overlay. LIVE retains the existing
     * EffectiveBalance contract, including ENFORCED failure-closed behaviour.
     */
    public EffectiveBalanceSnapshot captureForOperation(TradingAccount account, TradingMode tradingMode) {
        AccountService.requireMode(account,tradingMode);
        if (tradingMode != TradingMode.DEMO) return captureForOperation(account);
        Instant start = Instant.now();
        List<Balance> local = demoProjection().findAll(account.id());
        return snapshot(account,start,true,null,List.of(),Map.of(),Map.of(),Map.of(),local,local,false);
    }

    // Seul le diagnostic SHADOW en arriere-plan appelle cette collecte synchrone ;
    // ENFORCED la garde obligatoirement dans le fil de l'operation.
    EffectiveBalanceSnapshot captureDiagnostic(TradingAccount account) {
        if (account.accountMode()==TradingMode.DEMO) return captureForOperation(account,TradingMode.DEMO);
        config.validateConfiguration();
        Instant start=Instant.now();
        if(!usesOfficial()) return snapshot(account,start,true,null,List.of(),Map.of(),Map.of(),Map.of(),List.of(),projection.findAll(account.id()),false);
        boolean enforced=config.getMode()==EffectiveBalanceProperties.Mode.ENFORCED;
        long captureStart=System.nanoTime(), officialNanos=0;
        int factCount=0;
        boolean available=false;
        String reason="TRANSACTION_ACTIVE";
        try {
            if(TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
            reason="CURRENCY_UNSUPPORTED";
            if(account.baseCurrency()!=Asset.EUR) throw new TradingException(HttpStatus.SERVICE_UNAVAILABLE,"OFFICIAL_CURRENCY_UNSUPPORTED","Devise officielle non validée");
            reason="OVERLAY_READ_FAILED";
            var facts=adjustments.read(account.id());
            factCount=facts.size();
            reason="INVALID_OVERLAY";
            var seen=new HashSet<Long>();
            for(var fact:facts) {
                if((fact.ste()!=null && !fact.ste().equals(account.as400Ste()))
                        || (fact.nucli()!=null && !fact.nucli().equals(account.as400NucliTrading()))
                        || !seen.add(fact.orderId()) || fact.currency()!=Asset.EUR || !fact.metal().isMetal()
                        || fact.quantityOz()==null || fact.quantityOz().signum()<=0 || fact.grossAmount()==null || fact.grossAmount().signum()<=0)
                    throw unavailable();
            }
            // Double collecte : une imputation ou variation observée pendant l'acquisition invalide l'ensemble.
            reason="OFFICIAL_READ_FAILED";
            OfficialTradingBalanceReader.Reading first,second;
            long officialStart=System.nanoTime();
            try {
                first=official.read(account,facts);
                second=official.read(account,facts);
            } finally { officialNanos=System.nanoTime()-officialStart; }
            reason="INCONSISTENT_OR_EXPIRED_SNAPSHOT";
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
            var completed=snapshot(account,start,true,null,facts,second.balances(),pending,second.clientPosted(),result,
                    enforced?result:projection.findAll(account.id()),enforced);
            available=true;
            reason="OK";
            return completed;
        } catch(RuntimeException failure) {
            if(enforced) {
                if(failure instanceof TradingException trading) throw trading;
                throw unavailable();
            }
            return snapshot(account,start,false,"OFFICIAL_BALANCE_UNAVAILABLE",List.of(),Map.of(),Map.of(),Map.of(),List.of(),projection.findAll(account.id()),false);
        } finally {
            telemetry.record(account.id(),config.getMode(),available,System.nanoTime()-captureStart,officialNanos,factCount,reason);
        }
    }

    /** Libere uniquement la tache diagnostique SHADOW a l'arret du contexte. */
    @PreDestroy public void close() { shadow.close(); }
    /** @param snapshot lecture effectuée @return données opposables au mode courant, SHADOW restant local */
    public List<Balance> forOperation(EffectiveBalanceSnapshot snapshot) {
        return snapshot.decisionBalances();
    }
    /** Revalidation locale sous ACCOUNT : aucune connexion IBM i sous verrou.
     * @param account compte relu @param snapshot acquisition antérieure
     * @return soldes encore opposables @throws TradingException si faits ou fraîcheur modifiés */
    public List<Balance> validate(TradingAccount account,EffectiveBalanceSnapshot snapshot) {
        if (account.accountMode()==TradingMode.DEMO) return validate(account,snapshot,TradingMode.DEMO);
        // En LEGACY/SHADOW, la decision locale est toujours relue sous verrou ACCOUNT.
        if(!snapshot.enforced()) return projection.findAll(account.id());
        if(snapshot.accountId()!=account.id() || !Objects.equals(snapshot.ste(),account.as400Ste())
                || !Objects.equals(snapshot.nucliTrading(),account.as400NucliTrading()) || account.baseCurrency()!=Asset.EUR
                || !snapshot.available() || !Instant.now().isBefore(snapshot.startedAt().plus(config.getMaxSnapshotAge()))
                || !snapshot.facts().equals(adjustments.read(account.id()))) throw unavailable();
        return snapshot.decisionBalances();
    }
    /** Revalidates a DEMO decision only against its local projection. */
    public List<Balance> validate(TradingAccount account, EffectiveBalanceSnapshot snapshot, TradingMode tradingMode) {
        AccountService.requireMode(account,tradingMode);
        if (snapshot.accountId()!=account.id()) throw unavailable();
        if (tradingMode == TradingMode.DEMO) return demoProjection().findAll(account.id());
        return validate(account, snapshot);
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
    private DemoBalanceRepository demoProjection() {
        if (demoProjection == null) throw new IllegalStateException("DEMO_BALANCE_PROJECTION_UNAVAILABLE");
        return demoProjection;
    }
}
