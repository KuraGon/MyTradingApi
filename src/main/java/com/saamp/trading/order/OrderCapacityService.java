package com.saamp.trading.order;

import com.saamp.trading.account.*;
import com.saamp.trading.common.TradingException;
import com.saamp.trading.config.TradingProperties;
import com.saamp.trading.domain.*;
import com.saamp.trading.pricing.*;
import com.saamp.trading.reservation.*;
import com.saamp.trading.risk.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Revalide la capacité sous le verrou compte avec un instantané de prix déjà acquis. */
@Service
public class OrderCapacityService {
    private final EffectiveBalanceService balances;
    private final ReservationRepository reservations;
    private final MarginRateRepository margins;
    private final TradingProperties properties;

    /**
     * @param balances positions réelles
     * @param reservations engagements opposables
     * @param margins taux configurés
     * @param properties fraîcheur admise */
    public OrderCapacityService(EffectiveBalanceService balances, ReservationRepository reservations,
                                MarginRateRepository margins, TradingProperties properties) {
        this.balances=balances; this.reservations=reservations; this.margins=margins; this.properties=properties;
    }

    /**
     * Remplace atomiquement les trois enveloppes d'un ordre après contrôle complet.
     * L'appelant détient ACCOUNT puis ORDER ; aucun accès fournisseur n'est effectué ici.
     *
     * @param account compte verrouillé
     *
     * @param order ordre verrouillé
     *
     * @param quotes instantané client acquis hors verrou
     *
     * @param config configuration du métal
     *
     * @param expiry échéance persistée du preview
     *
     * @param execution contrôle strict de fraîcheur au submit
     *
     * @return montants réservés et projections de cet ordre
     * @throws TradingException si liquidité, capacité, limite ou cotation invalide
     */
    public Admission reserve(TradingAccount account, TradingOrder order, Map<Asset,ClientQuote> quotes,
                             AssetConfig config, OffsetDateTime expiry, boolean execution) {
        return reserve(account,order,quotes,quotes,config,expiry,execution);
    }

    /**
     * Conserve les cotations fraîches propres aux limites sans changer le prix indicatif du preview.
     * @param account compte verrouillé
     * @param order ordre verrouillé
     * @param quotes liquidations client pour la capacité B
     * @param limitQuotes prix marché acquis avec la fraîcheur d'exécution
     * @param config configuration du métal
     * @param expiry échéance persistée
     * @param execution admission au submit
     * @return engagements calculés selon leurs conventions respectives
     * @throws TradingException si prix, liquidité, capacité ou limite invalide
     */
    public Admission reserve(TradingAccount account, TradingOrder order, Map<Asset,ClientQuote> quotes,
                             Map<Asset,ClientQuote> limitQuotes, AssetConfig config,
                             OffsetDateTime expiry, boolean execution) {
        return reserve(account,order,quotes,limitQuotes,config,expiry,execution,null);
    }

    /** Valide localement une acquisition effectuée avant le verrou ACCOUNT.
     * @param account compte @param order ordre @param quotes prix risque @param limitQuotes prix limites
     * @param config actif @param expiry échéance @param execution submit @param acquired snapshot
     * @return engagements @throws TradingException si snapshot ou capacité invalides */
    public Admission reserve(TradingAccount account, TradingOrder order, Map<Asset,ClientQuote> quotes,
            Map<Asset,ClientQuote> limitQuotes, AssetConfig config, OffsetDateTime expiry, boolean execution,
            EffectiveBalanceSnapshot acquired) {
        if (reservations.hasActiveTransmittedLegacy(account.id()))
            throw failure("LEGACY_COMMITMENT_UNRESOLVED","Un engagement historique transmis doit être résolu avant une nouvelle admission");
        var snapshot=acquired==null?balances.findAll(account.id()):balances.validate(account,acquired);
        BigDecimal funds=BigDecimal.ZERO;
        BigDecimal position=BigDecimal.ZERO;
        var valued=new ArrayList<AccountPosition>();
        BigDecimal projectedGross=BigDecimal.ZERO;
        var quantities=new EnumMap<Asset,BigDecimal>(Asset.class);
        var rates=new EnumMap<Asset,BigDecimal>(Asset.class);
        for (var balance:snapshot) {
            quantities.put(balance.asset(),balance.quantity());
            if (balance.asset()==account.baseCurrency()) funds=balance.quantity();
        }
        quantities.putIfAbsent(order.asset(),BigDecimal.ZERO);
        for (var entry:quantities.entrySet()) {
            Asset asset=entry.getKey();
            if (!asset.isMetal()) continue;
            BigDecimal quantity=entry.getValue();
            if (asset==order.asset()) position=quantity;
            if (quantity.signum()==0 && asset!=order.asset()) continue;
            var quote=requireQuote(asset,quotes,execution);
            BigDecimal rate=margins.currentRate(account.id(),asset);
            rates.put(asset,rate);
            BigDecimal value=valuation(quantity,quote);
            valued.add(new AccountPosition(asset,quantity,quantity.signum()<0?quote.clientBuyPrice():quote.clientSellPrice(),
                    value,quote.priceAsOf().toInstant(),rate.multiply(new BigDecimal("100")),money(value.abs().multiply(rate))));
            BigDecimal projected=quantity;
            if (asset==order.asset()) projected=quantity.add(order.side()==OrderSide.BUY?order.quantityOz():order.quantityOz().negate());
            // Convention historique des limites : prix marché, arrondi après la somme des métaux.
            // La liquidation client ci-dessus reste réservée au calcul Risk / Free Equity.
            var limitQuote=account.positionLimit()!=null && projected.signum()!=0
                    ?requireQuote(asset,limitQuotes,true):quote;
            BigDecimal limitMark=projected.signum()>=0?limitQuote.marketBid():limitQuote.marketAsk();
            projectedGross=projectedGross.add(projected.multiply(limitMark).abs());
        }
        var quote=requireQuote(order.asset(),quotes,execution);
        BigDecimal anchor=order.indicativeClientPrice();
        BigDecimal envelope=anchor.multiply(order.side()==OrderSide.BUY
                ?BigDecimal.ONE.add(config.driftTolerance()):BigDecimal.ONE.subtract(config.driftTolerance()));
        if (envelope.signum()<=0) throw failure("INVALID_PRICE_ENVELOPE","Enveloppe de prix invalide");
        var settlementBounds=settlementPriceBounds(anchor,config);
        if (money(order.quantityOz().multiply(settlementBounds.minimum())).signum()<=0)
            throw failure("ORDER_AMOUNT_NOT_SETTLEABLE","Montant devise nul après arrondi dans l'enveloppe admise");
        BigDecimal cash=order.side()==OrderSide.BUY
                ?order.quantityOz().multiply(anchor).setScale(2,RoundingMode.CEILING)
                    .multiply(BigDecimal.ONE.add(config.driftTolerance())).setScale(6,RoundingMode.CEILING)
                :BigDecimal.ZERO;
        if (order.side()==OrderSide.BUY)
            cash=cash.max(money(order.quantityOz().multiply(settlementBounds.maximum()))).setScale(6,RoundingMode.CEILING);
        BigDecimal availableCash=funds.subtract(reservations.cashReserved(account.id(),account.baseCurrency(),order.id()));
        if (order.side()==OrderSide.BUY && availableCash.compareTo(cash)<0)
            throw failure("INSUFFICIENT_AVAILABLE_BALANCE","Liquidité devise insuffisante");
        var result=OrderRiskProjection.calculate(position,order.quantityOz(),
                reservations.closeReserved(account.id(),order.asset(),order.side(),order.id()),order.side(),
                quote.clientBuyPrice(),quote.clientSellPrice(),envelope,rates.get(order.asset()));
        var current=RiskCalculator.calculateAccountPositions(funds,valued);
        BigDecimal availableFree=current.freeEquity().subtract(reservations.riskReserved(account.id(),account.baseCurrency(),order.id()));
        availableFree=availableFree.subtract(unbackedTransmittedRisk(account,order.id(),quantities,rates,quotes,execution));
        BigDecimal projectedFree=availableFree.add(result.freeEquityDelta());
        if (availableFree.signum()<0) {
            if (result.closeQty().compareTo(order.quantityOz())!=0 || result.openQty().signum()!=0
                    || result.projectedPosition().abs().compareTo(position.abs())>=0
                    || result.freeEquityDelta().signum()<=0 || funds.add(result.cashDelta()).signum()<0)
                throw failure("INSUFFICIENT_FREE_EQUITY","Seule une fermeture améliorant la capacité est autorisée");
        } else if (projectedFree.signum()<0) {
            throw failure("INSUFFICIENT_FREE_EQUITY","Free Equity projetée insuffisante");
        }
        BigDecimal notional=order.quantityOz().multiply(order.side()==OrderSide.BUY?quote.clientBuyPrice():quote.clientSellPrice())
                .setScale(2,RoundingMode.CEILING);
        if (account.dealLimit()!=null && notional.compareTo(account.dealLimit())>0)
            throw failure("DEAL_LIMIT_EXCEEDED","Plafond par ordre dépassé");
        if (account.positionLimit()!=null && money(projectedGross).compareTo(account.positionLimit())>0)
            throw failure("POSITION_LIMIT_EXCEEDED","Exposition brute projetée au-dessus de la limite");
        reservations.releaseForOrder(order.id());
        put(account,order,account.baseCurrency(),ReservationKind.CASH,cash,expiry);
        put(account,order,order.asset(),ReservationKind.POSITION_CLOSE,result.closeQty(),expiry);
        put(account,order,account.baseCurrency(),ReservationKind.RISK,result.riskRequired(),expiry);
        return new Admission(cash,result.closeQty(),result.riskRequired(),result.projectedPosition(),
                current.netEquity().add(result.netEquityDelta()),current.marginRequirement().add(result.marginDelta()),projectedFree);
    }

    /** Reconstitue uniquement le supplément de risque des fermetures transmises devenues découvertes. */
    private BigDecimal unbackedTransmittedRisk(TradingAccount account,long excludedOrder,
            Map<Asset,BigDecimal> positions,Map<Asset,BigDecimal> rates,Map<Asset,ClientQuote> quotes,boolean execution) {
        var allocated=new EnumMap<Asset,BigDecimal>(Asset.class);
        BigDecimal extra=BigDecimal.ZERO;
        for (var commitment:reservations.activeCloseCommitments(account.id(),excludedOrder)) {
            BigDecimal position=positions.getOrDefault(commitment.asset(),BigDecimal.ZERO);
            BigDecimal closable=(commitment.side()==OrderSide.SELL?position:position.negate()).max(BigDecimal.ZERO);
            BigDecimal used=allocated.getOrDefault(commitment.asset(),BigDecimal.ZERO);
            BigDecimal covered=commitment.close().min(closable.subtract(used).max(BigDecimal.ZERO));
            allocated.put(commitment.asset(),used.add(covered));
            // Les DRAFT gardent leur attribution, sans anticiper leur exécution ni la fermeture d'autrui.
            if (!commitment.transmitted() || covered.compareTo(commitment.close())>=0) continue;
            var quote=requireQuote(commitment.asset(),quotes,execution);
            if (commitment.drift()==null)
                throw failure("ASSET_NOT_CONFIGURED","Configuration de l'engagement transmise introuvable");
            BigDecimal rate=rates.computeIfAbsent(commitment.asset(),asset->margins.currentRate(account.id(),asset));
            BigDecimal envelope=commitment.anchor().multiply(commitment.side()==OrderSide.BUY
                    ?BigDecimal.ONE.add(commitment.drift()):BigDecimal.ONE.subtract(commitment.drift()));
            if (envelope.signum()<=0) throw failure("INVALID_PRICE_ENVELOPE","Enveloppe de prix invalide");
            var projected=OrderRiskProjection.calculate(position,commitment.quantity(),closable.subtract(covered),
                    commitment.side(),quote.clientBuyPrice(),quote.clientSellPrice(),envelope,rate);
            extra=extra.add(projected.riskRequired().subtract(commitment.risk()).max(BigDecimal.ZERO));
        }
        return extra;
    }

    /**
     * Borne les prix client publiés que le contrôle de drift existant accepte réellement.
     * Le spread puis CEILING (BUY) / FLOOR (SELL) produisent la grille quoteScale ;
     * le drift porte déjà sur ce prix client, donc le spread ne doit pas être appliqué deux fois.
     * Les extrémités sont ouvertes à la demi-unité de l'arrondi de driftRatio, sans nouvelle tolérance.
     */
    private SettlementPriceBounds settlementPriceBounds(BigDecimal anchor,AssetConfig config) {
        BigDecimal ratioUnit=PriceMath.driftRatio(anchor,anchor).ulp();
        BigDecimal exclusiveRatio=config.driftTolerance().setScale(ratioUnit.scale(),RoundingMode.FLOOR)
                .add(ratioUnit.divide(BigDecimal.valueOf(2)));
        BigDecimal tick=BigDecimal.ONE.movePointLeft(config.quoteScale());
        BigDecimal minimum=anchor.multiply(BigDecimal.ONE.subtract(exclusiveRatio))
                .setScale(config.quoteScale(),RoundingMode.FLOOR).add(tick).max(BigDecimal.ZERO);
        BigDecimal maximum=anchor.multiply(BigDecimal.ONE.add(exclusiveRatio))
                .setScale(config.quoteScale(),RoundingMode.CEILING).subtract(tick);
        if (minimum.compareTo(maximum)>0)
            throw failure("ORDER_AMOUNT_NOT_SETTLEABLE","Aucun prix client réglable dans l'enveloppe admise");
        return new SettlementPriceBounds(minimum,maximum);
    }
    private record SettlementPriceBounds(BigDecimal minimum,BigDecimal maximum) { }

    private ClientQuote requireQuote(Asset asset, Map<Asset,ClientQuote> quotes, boolean execution) {
        var quote=quotes.get(asset);
        if (quote==null) throw failure("MARKET_PRICE_STALE","Positions modifiées, nouvelle cotation requise");
        Duration age=Duration.between(quote.priceAsOf().toInstant(),java.time.Instant.now());
        Duration max=execution?properties.getPricing().getExecutionMaxAge():properties.getPricing().getDisplayMaxAge();
        if (age.isNegative() || age.compareTo(max)>0) throw failure("MARKET_PRICE_STALE","Cotation expirée pendant l'admission");
        return quote;
    }
    private void put(TradingAccount account, TradingOrder order, Asset asset, ReservationKind kind, BigDecimal amount, OffsetDateTime expiry) {
        if (amount.signum()>0) reservations.upsert(account.id(),asset,amount,order.id(),kind,expiry);
    }
    private static BigDecimal valuation(BigDecimal quantity, ClientQuote quote) {
        return money(quantity.multiply(quantity.signum()<0?quote.clientBuyPrice():quote.clientSellPrice()));
    }
    private static BigDecimal money(BigDecimal amount) { return amount.setScale(2,RoundingMode.HALF_UP); }
    private static TradingException failure(String code,String message) { return new TradingException(HttpStatus.CONFLICT,code,message); }

    /**
     * @param cash liquidité
     * @param close fermeture
     * @param risk risque
     * @param position position projetée
     * @param netEquity équité projetée
     * @param margin marge projetée
     * @param freeEquity capacité disponible projetée */
    public record Admission(BigDecimal cash,BigDecimal close,BigDecimal risk,BigDecimal position,
                            BigDecimal netEquity,BigDecimal margin,BigDecimal freeEquity) { }
}
