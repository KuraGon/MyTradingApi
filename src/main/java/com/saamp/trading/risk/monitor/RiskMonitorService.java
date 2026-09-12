package com.saamp.trading.risk.monitor;

import com.saamp.trading.account.*;
import com.saamp.trading.common.TradingException;
import com.saamp.trading.domain.Asset;
import com.saamp.trading.pricing.*;
import com.saamp.trading.risk.*;
import com.saamp.trading.risk.monitor.RiskMonitorPolicy.Level;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Observe les positions sans crÃ©er d'ordre ni modifier la capacitÃ© d'admission. */
public final class RiskMonitorService {
    private final RiskMonitorRepository repository;
    private final AccountRepository accounts;
    private final EffectiveBalanceService balances;
    private final PricingService pricing;
    private final MarginRateRepository rates;
    private final RiskService risk;
    private final RiskMonitorProperties config;
    private final Clock clock;
    RiskMonitorService(RiskMonitorRepository repository,AccountRepository accounts,EffectiveBalanceService balances,
            PricingService pricing,MarginRateRepository rates,RiskService risk,RiskMonitorProperties config,Clock clock) {
        this.repository=repository;this.accounts=accounts;this.balances=balances;this.pricing=pricing;
        this.rates=rates;this.risk=risk;this.config=config;this.clock=clock;
    }
    record Observation(long accountId,long version,String fingerprint,Instant at,Level level,
                       BigDecimal coverage,String indicators,Instant priceAsOf,String error,EffectiveBalanceSnapshot balances) {
        Observation(long accountId,long version,String fingerprint,Instant at,Level level,
                BigDecimal coverage,String indicators,Instant priceAsOf,String error) {
            this(accountId,version,fingerprint,at,level,coverage,indicators,priceAsOf,error,null);
        }
        Observation withBalances(EffectiveBalanceSnapshot snapshot) {
            return new Observation(accountId,version,fingerprint,at,level,coverage,indicators,priceAsOf,error,snapshot);
        }
    }

    /** Parcourt les comptes indÃ©pendamment, avec une acquisition rÃ©seau hors transaction. */
    @Scheduled(fixedDelayString="${trading.risk-monitor.interval}")
    public void scan() {
        long after=0;
        while (true) {
            var ids=balances.usesOfficial()?repository.allCandidates(after,config.batchSize()):repository.candidates(after,config.batchSize());
            if (ids.isEmpty()) return;
            for (long id:ids) {
                try { observe(id); }
                catch (RuntimeException e) { org.slf4j.LoggerFactory.getLogger(getClass()).error("Risk Monitor observation failed accountId={} type={}",id,e.getClass().getSimpleName()); }
            }
            after=ids.getLast();
        }
    }

    /**
     * Observe uniquement le compte demand?, sans traiter d'autre fixture ou compte.
     * @param id compte ? observer
     * @return vrai si l'observation est encore applicable et persistÃ©e
     * @throws IllegalStateException si une transaction appelante englobe l'acquisition
     */
    public boolean observe(long id) {
        var observation=prepare(id);
        return repository.persist(observation,config,clock,()->{
            if(observation.balances()!=null) balances.validate(accounts.findById(id).orElseThrow(),observation.balances());
        });
    }

    Observation prepare(long id) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Risk Monitor acquisition requires no transaction");
        long version=repository.version(id);
        String fingerprint=repository.fingerprint(id);
        var account=accounts.findById(id).orElseThrow();
        EffectiveBalanceSnapshot acquired;
        try { acquired=balances.captureForOperation(account); }
        catch(TradingException failure) {
            return new Observation(id,version,fingerprint,clock.instant(),null,null,null,null,failure.getCode());
        }
        return calculate(id,version,fingerprint,account,balances.forOperation(acquired)).withBalances(acquired);
    }
    private Observation calculate(long id,long version,String fingerprint,TradingAccount account,List<Balance> snapshot) {
        var quotes=new EnumMap<Asset,ClientQuote>(Asset.class);
        var margin=new EnumMap<Asset,BigDecimal>(Asset.class);
        Instant priceAsOf=null;
        try {
            for (var b:snapshot) if (b.asset().isMetal() && b.quantity().signum()!=0) {
                // Un taux absent reste une erreur de calcul explicite, mÃªme si le prix manque aussi.
                margin.put(b.asset(),rates.currentRate(id,b.asset()));
            }
            for (var b:snapshot) if (b.asset().isMetal() && b.quantity().signum()!=0) {
                ClientQuote quote;
                try { quote=pricing.quoteForMonitor(account.companyId(),b.asset(),account.baseCurrency(),config.maxPriceAge()); }
                catch (TradingException e) { throw e; }
                catch (RuntimeException e) {
                    return new Observation(id,version,fingerprint,clock.instant(),Level.PRICE_STALE,null,null,priceAsOf,"PRICE_ACQUISITION_FAILED");
                }
                quotes.put(b.asset(),quote);
                Instant used=quote.priceAsOf().toInstant();
                if (priceAsOf==null || used.isBefore(priceAsOf)) priceAsOf=used;
                if (used.isAfter(clock.instant()) || used.plus(config.maxPriceAge()).isBefore(clock.instant()))
                    return new Observation(id,version,fingerprint,clock.instant(),Level.PRICE_STALE,null,null,priceAsOf,"MARKET_PRICE_STALE");
            }
            var calculation=risk.evaluate(account,snapshot,quotes,margin);
            var result=calculation.published();
            Map<String,Object> indicators=new LinkedHashMap<>();
            indicators.put("totalFunds",result.totalFunds());indicators.put("positionValuation",result.positionValuation());
            indicators.put("grossPosition",result.grossPosition());indicators.put("netEquity",result.netEquity());
            indicators.put("marginRequirement",result.marginRequirement());indicators.put("freeEquity",result.freeEquity());
            indicators.put("coverageCalculated",calculation.coverageBeforeDisplay());indicators.put("coverageDisplayed",result.coveragePct());
            indicators.put("currency",account.baseCurrency().name());
            return new Observation(id,version,fingerprint,clock.instant(),RiskMonitorPolicy.classify(calculation,config),
                    calculation.coverageBeforeDisplay(),repository.encode(indicators),priceAsOf,null);
        } catch (TradingException e) {
            String code=e.getCode();
            boolean price=Set.of("MARKET_PRICE_STALE","MARKET_PRICE_MISSING").contains(code);
            String safe=Set.of("MARKET_PRICE_STALE","MARKET_PRICE_MISSING","MARGIN_RATE_MISSING","SPREAD_NOT_CONFIGURED","ASSET_NOT_CONFIGURED","ASSET_DISABLED").contains(code)?code:"CALCULATION_FAILED";
            return new Observation(id,version,fingerprint,clock.instant(),price?Level.PRICE_STALE:null,null,null,priceAsOf,safe);
        }
    }
}
