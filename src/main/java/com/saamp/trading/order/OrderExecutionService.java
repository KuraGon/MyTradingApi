package com.saamp.trading.order;

import com.saamp.trading.account.*;
import com.saamp.trading.common.*;
import com.saamp.trading.config.TradingProperties;
import com.saamp.trading.domain.*;
import com.saamp.trading.pricing.*;
import com.saamp.trading.provider.*;
import com.saamp.trading.reservation.*;
import com.saamp.trading.security.TradingDemoGuard;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Sépare l'admission atomique de l'appel externe irréversible. */
@Service
public class OrderExecutionService {
    private final AccountRepository accounts;
    private final EffectiveBalanceService balances;
    private final PricingRepository pricingRepository;
    private final PricingService pricing;
    private final ReservationRepository reservationRepository;
    private final OrderRepository orders;
    private final TradingExecutionProviderRouter providers;
    private final ExecutionEventHandler executionEvents;
    private final ExecutionGateRepository gate;
    private final OrderCapacityService capacity;
    private final TradingProperties properties;
    private final TransactionTemplate transactions;
    private final TradingDemoGuard demoGuard;

    /**
     * Réunit le contrôle local et le fournisseur en conservant deux phases séparées.
     *
     * @param accounts comptes
     * @param balances positions
     * @param pricingRepository configuration
     *
     * @param pricing cotations
     * @param reservationRepository engagements
     * @param orders ordres
     *
     * @param provider fournisseur
     * @param executionEvents règlements
     * @param gate autorisation globale
     *
     * @param capacity admission B
     * @param properties échéances
     * @param transactionManager transactions locales
     */
    public OrderExecutionService(AccountRepository accounts, EffectiveBalanceService balances, PricingRepository pricingRepository,
                                 PricingService pricing, ReservationRepository reservationRepository, OrderRepository orders,
                                 TradingProvider provider, ExecutionEventHandler executionEvents, ExecutionGateRepository gate,
                                 OrderCapacityService capacity, TradingProperties properties, PlatformTransactionManager transactionManager) {
        this(accounts, balances, pricingRepository, pricing, reservationRepository, orders, provider, executionEvents, gate,
                capacity, properties, transactionManager, new TradingDemoGuard(properties));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OrderExecutionService(AccountRepository accounts, EffectiveBalanceService balances, PricingRepository pricingRepository,
                                 PricingService pricing, ReservationRepository reservationRepository, OrderRepository orders,
                                 TradingProvider provider, ExecutionEventHandler executionEvents, ExecutionGateRepository gate,
                                 OrderCapacityService capacity, TradingProperties properties, PlatformTransactionManager transactionManager,
                                 TradingDemoGuard demoGuard) {
        this.accounts=accounts; this.balances=balances; this.pricingRepository=pricingRepository; this.pricing=pricing;
        this.reservationRepository=reservationRepository; this.orders=orders; this.providers=new TradingExecutionProviderRouter(provider,new SimulatedTradingProvider(pricingRepository));
        this.executionEvents=executionEvents; this.gate=gate; this.capacity=capacity; this.properties=properties;
        this.transactions=new TransactionTemplate(transactionManager);
        this.demoGuard=demoGuard;
    }

    /**
     * Prépare un brouillon et ses engagements B avec idempotence persistante.
     *
     * @param companyId société
     * @param userId acteur
     * @param request demande
     *
     * @return prix indicatif et réservations temporaires
     * @throws TradingException si compte, cotation ou capacité invalide
     */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public OrderPreviewResponse preview(long companyId,long userId,OrderPreviewRequest request) {
        return preview(companyId, userId, request, TradingMode.LIVE);
    }

    /** Fixes the caller mode when the draft and all of its reservations are created. */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public OrderPreviewResponse preview(long companyId,long userId,OrderPreviewRequest request,TradingMode tradingMode) {
        final TradingMode mode=tradingMode==null?TradingMode.LIVE:tradingMode;
        ensureDemoSubmissionAllowed(mode);
        TradingAccount account=accountForCompany(companyId);
        AccountService.requireMode(account,mode);
        ensureTradingAllowed(account);
        var balanceSnapshot=balances.captureForOperation(account,mode);
        var existing=orders.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) { ensureOwnership(existing.get(),companyId); ensureMode(existing.get(),mode); return replay(existing.get()); }
        BigDecimal qty=TroyWeightConverter.toTroyOunces(request.quantity(),request.unit());
        var quotes=quotes(account,request.asset(),false,balances.forOperation(balanceSnapshot),mode);
        var limitQuotes=account.positionLimit()==null?quotes:quotes(account,request.asset(),true,balances.forOperation(balanceSnapshot),mode);
        return transactions.execute(status->{
            var locked=accounts.lockById(account.id()).orElseThrow();
            AccountService.requireMode(locked,mode);
            ensureTradingAllowed(locked);
            var duplicate=orders.findByIdempotencyKey(request.idempotencyKey());
            if (duplicate.isPresent()) { ensureOwnership(duplicate.get(),companyId); ensureMode(duplicate.get(),mode); return replay(duplicate.get()); }
            var config=config(request.asset(),qty);
            var quote=quotes.get(request.asset());
            boolean buy=request.side()==OrderSide.BUY;
            BigDecimal indicative=buy?quote.clientBuyPrice():quote.clientSellPrice();
            long id=orders.insertDraft(locked.id(),companyId,request.asset(),quote.pair(),request.side(),request.quantity(),request.unit(),qty,
                    buy?quote.marketAsk():quote.marketBid(),buy?quote.clientBuyPriceRaw():quote.clientSellPriceRaw(),indicative,
                    buy?quote.spreadBuy():quote.spreadSell(),quote.spreadConfigVersion(),request.idempotencyKey(),
                    ClientOrderIdFactory.fromIdempotencyKey(request.idempotencyKey()),mode);
            if (id<0) {
                var other=orders.findByIdempotencyKey(request.idempotencyKey()).orElseThrow();
                ensureOwnership(other,companyId);
                ensureMode(other,mode);
                return replay(other);
            }
            var order=orders.lockById(id).orElseThrow();
            var expiry=order.createdAt().plus(properties.getReservations().getTtl());
            var admission=capacity.reserve(locked,order,quotes,limitQuotes,config,expiry,false,balanceSnapshot);
            return new OrderPreviewResponse(id,request.asset(),request.side(),qty,quote.pair(),indicative,
                    quote.priceAsOf(),expiry,admission.cash(),admission.close());
        });
    }

    /**
     * Revalide sous verrou et attribue une seule fois le droit d'appeler le fournisseur.
     *
     * @param orderId ordre
     * @param companyId société
     * @param userId acteur
     *
     * @return état persistant après admission ou résultat fournisseur
     * @throws TradingException si l'ordre ne peut être transmis
     */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public TradingOrder submit(long orderId,long companyId,long userId) {
        return submit(orderId, companyId, userId, TradingMode.LIVE);
    }

    /** Carries the JWT mode so a DEMO request is rejected before any real provider call. */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public TradingOrder submit(long orderId,long companyId,long userId,TradingMode tradingMode) {
        final TradingMode mode=tradingMode==null?TradingMode.LIVE:tradingMode;
        ensureDemoSubmissionAllowed(mode);
        var initial=orders.findById(orderId).orElseThrow(()->failure("ORDER_NOT_FOUND","Ordre introuvable",HttpStatus.NOT_FOUND));
        ensureOwnership(initial,companyId);
        ensureMode(initial,mode);
        var account=accountForCompany(companyId);
        AccountService.requireMode(account,mode);
        ensureTradingAllowed(account);
        TradingProvider provider=providers.forMode(initial.tradingMode());
        if (mode == TradingMode.LIVE && !gate.isOpen()) throw failure("EXECUTION_BLOCKED","Nouvelles transmissions bloquées",HttpStatus.SERVICE_UNAVAILABLE);
        if (!provider.supportsSpotOrderSubmission()) throw failure("PROVIDER_EXECUTION_NOT_READY","Transmission SPOT désactivée",HttpStatus.SERVICE_UNAVAILABLE);
        if (transmitted(initial)) return initial;
        if (initial.status()!=OrderStatus.DRAFT) throw failure("ORDER_NOT_SUBMITTABLE","Ordre non transmissible",HttpStatus.CONFLICT);
        var balanceSnapshot=balances.captureForOperation(account,mode);
        var quotes=quotes(account,initial.asset(),true,balances.forOperation(balanceSnapshot),mode);
        var admitted=transactions.execute(status->{
            var lockedAccount=accounts.lockById(account.id()).orElseThrow();
            var order=orders.lockById(orderId).orElseThrow();
            ensureOwnership(order,companyId);
            ensureMode(order,mode);
            AccountService.requireMode(lockedAccount,mode);
            ensureTradingAllowed(lockedAccount);
            if (transmitted(order)) return new Submission(order,lockedAccount,false,null);
            if (order.status()!=OrderStatus.DRAFT) throw failure("ORDER_NOT_SUBMITTABLE","Ordre non transmissible",HttpStatus.CONFLICT);
            var expiry=expiry(order);
            if (!expiry.isAfter(OffsetDateTime.now())) {
                orders.markExpired(orderId);
                reservationRepository.expireForOrder(orderId);
                return new Submission(order,lockedAccount,false,failure("RESERVATION_EXPIRED","Nouvelle cotation requise",HttpStatus.CONFLICT));
            }
            var config=config(order.asset(),order.quantityOz());
            var fresh=quotes.get(order.asset());
            BigDecimal price=order.side()==OrderSide.BUY?fresh.clientBuyPrice():fresh.clientSellPrice();
            if (PriceMath.driftRatio(order.indicativeClientPrice(),price).compareTo(config.driftTolerance())>0) {
                orders.markRejected(orderId,"PRICE_MOVED","Le prix a dépassé la tolérance de dérive");
                reservationRepository.releaseForOrder(orderId);
                return new Submission(order,lockedAccount,false,new TradingException(HttpStatus.CONFLICT,"PRICE_MOVED",
                        "Nouvelle cotation requise",Map.of("currentClientPrice",price,"priceAsOf",fresh.priceAsOf(),"pair",fresh.pair())));
            }
            capacity.reserve(lockedAccount,order,quotes,quotes,config,expiry,true,balanceSnapshot);
            if (orders.markPending(orderId)!=1) throw new IllegalStateException("Concurrent order transition");
            return new Submission(order,lockedAccount,true,null);
        });
        if (admitted.error()!=null) throw admitted.error();
        if (!admitted.send()) return admitted.order();
        // La transaction locale est commitée avant cet appel irréversible.
        OrderAcknowledgement ack;
        try {
            ack=provider.submitSpotOrder(new SpotOrderRequest(admitted.order().clOrdId(),admitted.order().pair(),
                    admitted.order().side(),admitted.order().quantityOz(),admitted.order().tradingMode()));
        } catch (RuntimeException uncertain) {
            orders.markPendingUnknown(orderId,"PROVIDER_UNCERTAIN",uncertain.getClass().getSimpleName());
            return orders.findById(orderId).orElseThrow();
        }
        if (ack.state()==AcknowledgementState.IN_PROCESS) orders.markPendingUnknown(orderId,"IN_PROCESS",ack.errorMessage());
        else if (ack.state()==AcknowledgementState.REJECTED) executionEvents.handleRejected(admitted.order(),ack.errorCode(),ack.errorMessage());
        else executionEvents.handleFilled(admitted.order(),admitted.account(),ack.executionId(),ack.rate(),quotes.get(initial.asset()),"user:"+userId);
        return orders.findById(orderId).orElseThrow();
    }

    private Map<Asset,ClientQuote> quotes(TradingAccount account,Asset traded,boolean execution,List<Balance> snapshot,TradingMode tradingMode) {
        var assets=EnumSet.of(traded);
        for (var balance:snapshot)
            if (balance.asset().isMetal() && balance.quantity().signum()!=0) assets.add(balance.asset());
        for (var commitment:reservationRepository.activeCloseCommitments(account.id(),-1,tradingMode))
            if (commitment.transmitted()) assets.add(commitment.asset());
        var result=new EnumMap<Asset,ClientQuote>(Asset.class);
        for (var asset:assets) result.put(asset,execution?pricing.quoteForExecution(account.companyId(),asset,account.baseCurrency())
                :pricing.quoteForDisplay(account.companyId(),asset,account.baseCurrency()));
        return result;
    }
    private AssetConfig config(Asset asset,BigDecimal quantity) {
        var config=pricingRepository.findAssetConfig(asset).orElseThrow(()->failure("ASSET_NOT_CONFIGURED","Actif non configuré",HttpStatus.CONFLICT));
        if (!config.enabled()) throw failure("ASSET_DISABLED","Actif désactivé",HttpStatus.CONFLICT);
        if (quantity.compareTo(config.minQuantityOz())<0) throw failure("QUANTITY_TOO_SMALL","Quantité sous le minimum",HttpStatus.BAD_REQUEST);
        return config;
    }
    private OrderPreviewResponse replay(TradingOrder order) {
        return new OrderPreviewResponse(order.id(),order.asset(),order.side(),order.quantityOz(),order.pair(),
                order.indicativeClientPrice(),order.createdAt(),expiry(order),BigDecimal.ZERO,BigDecimal.ZERO);
    }
    private OffsetDateTime expiry(TradingOrder order) {
        return reservationRepository.findEarliestExpiryForOrder(order.id()).orElse(order.createdAt().plus(properties.getReservations().getTtl()));
    }
    private boolean transmitted(TradingOrder order) {
        return order.status()==OrderStatus.PENDING || order.status()==OrderStatus.PENDING_UNKNOWN || order.status()==OrderStatus.FILLED;
    }
    private TradingAccount accountForCompany(long companyId) {
        return accounts.findByCompanyId(companyId).orElseThrow(()->failure("TRADING_ACCOUNT_NOT_FOUND","Compte Trading absent",HttpStatus.NOT_FOUND));
    }
    private void ensureTradingAllowed(TradingAccount account) {
        if (account.status()!=AccountStatus.ACTIVE) throw failure("ACCOUNT_NOT_ACTIVE","Compte Trading non actif",HttpStatus.CONFLICT);
    }
    private void ensureDemoSubmissionAllowed(TradingMode tradingMode) {
        demoGuard.assertSubmissionAllowed(tradingMode);
    }
    private void ensureOwnership(TradingOrder order,long companyId) {
        if (order.companyId()!=companyId) throw failure("ORDER_NOT_FOUND","Ordre introuvable",HttpStatus.NOT_FOUND);
    }
    private void ensureMode(TradingOrder order,TradingMode mode) {
        if (order.tradingMode()!=mode) throw failure("ORDER_TRADING_MODE_MISMATCH",
                "Le mode de l'ordre ne correspond pas au contexte du jeton.",HttpStatus.CONFLICT);
    }
    private static TradingException failure(String code,String message,HttpStatus status) { return new TradingException(status,code,message); }
    private record Submission(TradingOrder order,TradingAccount account,boolean send,TradingException error) { }
}
