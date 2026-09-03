package com.saamp.trading.order;

import com.saamp.trading.account.AccountRepository;
import com.saamp.trading.account.BalanceRepository;
import com.saamp.trading.account.TradingAccount;
import com.saamp.trading.common.ClientOrderIdFactory;
import com.saamp.trading.common.TradingException;
import com.saamp.trading.common.TroyWeightConverter;
import com.saamp.trading.domain.*;
import com.saamp.trading.pricing.*;
import com.saamp.trading.provider.*;
import com.saamp.trading.reservation.ReservationRepository;
import com.saamp.trading.reservation.ReservationService;
import com.saamp.trading.risk.MarginRateRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Implements the irreversible SPOT workflow with pre-order coverage, persistent reservations and idempotence. */
@Service
public class OrderExecutionService {
    private final AccountRepository accounts;
    private final BalanceRepository balances;
    private final PricingRepository pricingRepository;
    private final PricingService pricing;
    private final ReservationService reservations;
    private final ReservationRepository reservationRepository;
    private final MarginRateRepository marginRates;
    private final OrderRepository orders;
    private final TradingProvider provider;
    private final ExecutionEventHandler executionEvents;
    private final ExecutionGateRepository gate;

    public OrderExecutionService(AccountRepository accounts, BalanceRepository balances, PricingRepository pricingRepository,
                                 PricingService pricing, ReservationService reservations, ReservationRepository reservationRepository,
                                 MarginRateRepository marginRates, OrderRepository orders, TradingProvider provider,
                                 ExecutionEventHandler executionEvents, ExecutionGateRepository gate) {
        this.accounts=accounts; this.balances=balances; this.pricingRepository=pricingRepository; this.pricing=pricing;
        this.reservations=reservations; this.reservationRepository=reservationRepository; this.marginRates=marginRates;
        this.orders=orders; this.provider=provider; this.executionEvents=executionEvents; this.gate=gate;
    }

    @Transactional
    public OrderPreviewResponse preview(long companyId, long userId, OrderPreviewRequest request) {
        TradingAccount account = accountForCompany(companyId);
        ensureTradingAllowed(account);

        var existing = orders.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            TradingOrder o = existing.get();
            ensureOwnership(o, companyId);
            return new OrderPreviewResponse(o.id(),o.asset(),o.side(),o.quantityOz(),o.pair(),o.indicativeClientPrice(),o.createdAt(),
                    BigDecimal.ZERO,BigDecimal.ZERO,o.idempotencyKey());
        }

        BigDecimal qtyOz = TroyWeightConverter.toTroyOunces(request.quantity(), request.unit());
        AssetConfig config = pricingRepository.findAssetConfig(request.asset())
                .orElseThrow(() -> new TradingException(HttpStatus.CONFLICT,"ASSET_NOT_CONFIGURED","Actif non configuré"));
        if (!config.enabled()) throw new TradingException(HttpStatus.CONFLICT,"ASSET_DISABLED","Actif désactivé");
        if (qtyOz.compareTo(config.minQuantityOz()) < 0) throw new TradingException(HttpStatus.BAD_REQUEST,"QUANTITY_TOO_SMALL","Quantité sous le minimum autorisé");

        ClientQuote quote = pricing.quoteForDisplay(companyId, request.asset(), account.baseCurrency());
        BigDecimal indicativeClient = request.side()==OrderSide.BUY ? quote.clientBuyPrice() : quote.clientSellPrice();
        BigDecimal indicativeClientRaw = request.side()==OrderSide.BUY ? quote.clientBuyPriceRaw() : quote.clientSellPriceRaw();
        BigDecimal indicativeMarket = request.side()==OrderSide.BUY ? quote.marketAsk() : quote.marketBid();
        BigDecimal notional = qtyOz.multiply(indicativeClient).setScale(2,RoundingMode.CEILING);
        if (account.dealLimit()!=null && notional.compareTo(account.dealLimit())>0)
            throw new TradingException(HttpStatus.CONFLICT,"DEAL_LIMIT_EXCEEDED","Plafond par ordre dépassé");
        if (account.positionLimit()!=null) {
            BigDecimal projectedGross = projectedGrossPosition(account, request.asset(), request.side(), qtyOz);
            if (projectedGross.compareTo(account.positionLimit()) > 0)
                throw new TradingException(HttpStatus.CONFLICT,"POSITION_LIMIT_EXCEEDED","Exposition brute projetée au-dessus de la limite");
        }

        String clOrdId = ClientOrderIdFactory.fromIdempotencyKey(request.idempotencyKey());
        BigDecimal appliedSpread = request.side()==OrderSide.BUY ? quote.spreadBuy() : quote.spreadSell();
        long orderId = orders.insertDraft(account.id(),companyId,request.asset(),quote.pair(),request.side(),request.quantity(),request.unit(),qtyOz,
                indicativeMarket,indicativeClientRaw,indicativeClient,appliedSpread,quote.spreadConfigVersion(),request.idempotencyKey(),clOrdId);
        if (orderId < 0) {
            TradingOrder duplicate = orders.findByIdempotencyKey(request.idempotencyKey()).orElseThrow();
            ensureOwnership(duplicate, companyId);
            return new OrderPreviewResponse(duplicate.id(),duplicate.asset(),duplicate.side(),duplicate.quantityOz(),duplicate.pair(),
                    duplicate.indicativeClientPrice(),duplicate.createdAt(),BigDecimal.ZERO,BigDecimal.ZERO,duplicate.idempotencyKey());
        }

        BigDecimal reservedCash = BigDecimal.ZERO;
        BigDecimal reservedMetal = BigDecimal.ZERO;
        if (request.side()==OrderSide.BUY) {
            BigDecimal worstCase = notional.multiply(BigDecimal.ONE.add(config.driftTolerance())).setScale(6,RoundingMode.CEILING);
            reservations.reserve(account.id(),account.baseCurrency(),worstCase,orderId);
            reservedCash = worstCase;
        } else {
            BigDecimal availableMetal = reservations.available(account.id(),request.asset()).max(BigDecimal.ZERO);
            BigDecimal covered = qtyOz.min(availableMetal);
            if (covered.signum()>0) {
                reservations.reserve(account.id(),request.asset(),covered,orderId);
                reservedMetal = covered;
            }
            BigDecimal uncovered = qtyOz.subtract(covered);
            if (uncovered.signum()>0) {
                BigDecimal marginRate = marginRates.currentRate(account.id(), request.asset());
                BigDecimal required = uncovered.multiply(indicativeClient).multiply(BigDecimal.ONE.add(marginRate)).setScale(6,RoundingMode.CEILING);
                reservations.reserve(account.id(),account.baseCurrency(),required,orderId);
                reservedCash = required;
            }
        }
        return new OrderPreviewResponse(orderId,request.asset(),request.side(),qtyOz,quote.pair(),indicativeClient,quote.priceAsOf(),reservedCash,reservedMetal,request.idempotencyKey());
    }

    public TradingOrder submit(long orderId, long companyId, long userId) {
        TradingOrder order = orders.findById(orderId).orElseThrow(() -> new TradingException(HttpStatus.NOT_FOUND,"ORDER_NOT_FOUND","Ordre introuvable"));
        ensureOwnership(order,companyId);
        TradingAccount account = accountForCompany(companyId);
        ensureTradingAllowed(account);
        if (!gate.isOpen()) throw new TradingException(HttpStatus.SERVICE_UNAVAILABLE,"EXECUTION_BLOCKED","Nouvelles transmissions bloquées par la réconciliation");
        if (!provider.supportsSpotOrderSubmission())
            throw new TradingException(HttpStatus.SERVICE_UNAVAILABLE,"PROVIDER_EXECUTION_NOT_READY","Transmission SPOT désactivée tant que le contrat /Trade n'est pas validé en UAT");
        if (order.status()==OrderStatus.FILLED || order.status()==OrderStatus.PENDING || order.status()==OrderStatus.PENDING_UNKNOWN) return order;
        if (order.status()!=OrderStatus.DRAFT) throw new TradingException(HttpStatus.CONFLICT,"ORDER_NOT_SUBMITTABLE","Ordre non transmissible dans cet état");
        if (!reservationRepository.hasActiveForOrder(orderId)) {
            orders.markExpired(orderId);
            throw new TradingException(HttpStatus.CONFLICT,"RESERVATION_EXPIRED","Réservation expirée, nouvelle cotation requise");
        }

        ClientQuote fresh = pricing.quoteForExecution(companyId, order.asset(), account.baseCurrency());
        AssetConfig config = pricingRepository.findAssetConfig(order.asset()).orElseThrow();
        BigDecimal freshClient = order.side()==OrderSide.BUY ? fresh.clientBuyPrice() : fresh.clientSellPrice();
        BigDecimal drift = PriceMath.driftRatio(order.indicativeClientPrice(),freshClient);
        if (drift.compareTo(config.driftTolerance())>0) {
            orders.markRejected(orderId,"PRICE_MOVED","Le prix a dépassé la tolérance de dérive");
            reservations.releaseForOrder(orderId);
            throw new TradingException(HttpStatus.CONFLICT,"PRICE_MOVED","Le prix a évolué au-delà de la tolérance; nouvelle cotation requise");
        }

        orders.markPending(orderId);
        OrderAcknowledgement ack;
        try {
            ack = provider.submitSpotOrder(new SpotOrderRequest(order.clOrdId(),order.pair(),order.side(),order.quantityOz()));
        } catch (RuntimeException providerFailure) {
            orders.markPendingUnknown(orderId,"PROVIDER_UNCERTAIN",providerFailure.getMessage());
            return orders.findById(orderId).orElseThrow();
        }
        if (ack.state()==AcknowledgementState.IN_PROCESS) {
            orders.markPendingUnknown(orderId,"IN_PROCESS",ack.errorMessage());
        } else if (ack.state()==AcknowledgementState.REJECTED) {
            orders.markRejected(orderId,ack.errorCode(),ack.errorMessage());
            reservations.releaseForOrder(orderId);
        } else {
            executionEvents.handleFilled(order, account, ack.executionId(), ack.rate(), fresh, "user:" + userId);
        }
        return orders.findById(orderId).orElseThrow();
    }

    private BigDecimal projectedGrossPosition(TradingAccount account, Asset tradedAsset, OrderSide side, BigDecimal quantityOz) {
        BigDecimal gross = BigDecimal.ZERO;
        for (Asset metal : Asset.metals()) {
            BigDecimal quantity = balances.find(account.id(), metal).map(b -> b.quantity()).orElse(BigDecimal.ZERO);
            if (metal == tradedAsset) quantity = quantity.add(side == OrderSide.BUY ? quantityOz : quantityOz.negate());
            if (quantity.signum() == 0) continue;
            ClientQuote q = pricing.quoteForExecution(account.companyId(), metal, account.baseCurrency());
            BigDecimal mark = quantity.signum() >= 0 ? q.marketBid() : q.marketAsk();
            gross = gross.add(quantity.multiply(mark).abs());
        }
        return gross.setScale(2, RoundingMode.HALF_UP);
    }

    private TradingAccount accountForCompany(long companyId) {
        return accounts.findByCompanyId(companyId).orElseThrow(() -> new TradingException(HttpStatus.NOT_FOUND,"TRADING_ACCOUNT_NOT_FOUND","Compte Trading absent"));
    }
    private void ensureTradingAllowed(TradingAccount account) {
        if (account.status()!=AccountStatus.ACTIVE)
            throw new TradingException(HttpStatus.CONFLICT,"ACCOUNT_NOT_ACTIVE","Compte Trading non actif: "+account.status());
    }
    private void ensureOwnership(TradingOrder order,long companyId) {
        if (order.companyId()!=companyId) throw new TradingException(HttpStatus.NOT_FOUND,"ORDER_NOT_FOUND","Ordre introuvable");
    }
}
