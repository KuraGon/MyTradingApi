package com.saamp.trading.order;

import com.saamp.trading.account.TradingAccount;
import com.saamp.trading.account.AccountRepository;
import com.saamp.trading.domain.OrderStatus;
import com.saamp.trading.domain.OrderSide;
import com.saamp.trading.ledger.LedgerService;
import com.saamp.trading.pricing.AssetConfig;
import com.saamp.trading.pricing.ClientQuote;
import com.saamp.trading.pricing.PriceMath;
import com.saamp.trading.pricing.PricingRepository;
import com.saamp.trading.reservation.ReservationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Règle une exécution une seule fois sous les verrous du compte et de son ordre.
 * La transaction comptable reste distincte de la transmission irréversible.
 */
@Service
public class ExecutionEventHandler {
    private final PricingRepository pricing;
    private final LedgerService ledger;
    private final OrderRepository orders;
    private final ReservationService reservations;
    private final AccountRepository accounts;

    /**
     * @param pricing prix publiés
     * @param ledger comptabilité atomique
     * @param orders états persistants
     * @param reservations engagements à solder
     * @param accounts verrou commun de capacité
     */
    public ExecutionEventHandler(PricingRepository pricing, LedgerService ledger, OrderRepository orders,
                                 ReservationService reservations, AccountRepository accounts) {
        this.pricing = pricing;
        this.ledger = ledger;
        this.orders = orders;
        this.reservations = reservations;
        this.accounts = accounts;
    }

    /**
     * Rend le règlement idempotent et atomique même lorsque plusieurs résolutions arrivent ensemble.
     * @param order ordre dont l'identité permet la relecture verrouillée
     * @param account compte indicatif, relu sous le verrou commun
     * @param executionId référence de l'exécution fournisseur
     * @param marketRate prix effectivement exécuté
     * @param quoteAtSubmission spreads client associés à la transmission
     * @param actor origine de l'écriture comptable
     * @throws IllegalStateException si l'ordre n'a pas été transmis
     */
    @Transactional
    public void handleFilled(TradingOrder order, TradingAccount account, String executionId,
                             BigDecimal marketRate, ClientQuote quoteAtSubmission, String actor) {
        account = accounts.lockById(order.accountId()).orElseThrow();
        order = orders.lockById(order.id()).orElseThrow();
        if (order.status()==OrderStatus.FILLED) return;
        if (order.status()!=OrderStatus.PENDING && order.status()!=OrderStatus.PENDING_UNKNOWN)
            throw new IllegalStateException("Execution requires a submitted order");
        BigDecimal spread = order.side() == OrderSide.BUY ? quoteAtSubmission.spreadBuy() : quoteAtSubmission.spreadSell();
        AssetConfig config = pricing.findAssetConfig(order.asset()).orElseThrow();
        BigDecimal clientPriceRaw = PriceMath.rawClientPrice(marketRate, spread, order.side());
        BigDecimal clientPrice = PriceMath.clientPrice(marketRate, spread, order.side(), config.quoteScale());
        BigDecimal cashAmountRaw = order.quantityOz().multiply(clientPrice);
        BigDecimal gross = cashAmountRaw.setScale(2, RoundingMode.HALF_UP);
        BigDecimal revenue = order.quantityOz().multiply(clientPrice.subtract(marketRate).abs()).setScale(6, RoundingMode.HALF_UP);
        BigDecimal metalDelta = order.side() == OrderSide.BUY ? order.quantityOz() : order.quantityOz().negate();
        BigDecimal cashDelta = order.side() == OrderSide.BUY ? gross.negate() : gross;

        ledger.postTrade(account.id(), order.asset(), metalDelta, account.baseCurrency(), cashDelta, order.id(), actor);
        orders.markFilled(order.id(), executionId, marketRate, clientPriceRaw, clientPrice, spread, quoteAtSubmission.spreadConfigVersion(), revenue, gross);
        reservations.consumeForOrder(order.id());
    }

    /**
     * Libère les engagements dans la même transaction que le rejet confirmé, sans défaire un FILLED.
     * @param order ordre à relire sous verrou
     * @param errorCode code de rejet confirmé
     * @param errorMessage explication du rejet
     * @throws IllegalStateException si l'ordre n'a pas été transmis
     */
    @Transactional
    public void handleRejected(TradingOrder order, String errorCode, String errorMessage) {
        accounts.lockById(order.accountId()).orElseThrow();
        order = orders.lockById(order.id()).orElseThrow();
        if (order.status()==OrderStatus.FILLED || order.status()==OrderStatus.REJECTED) return;
        if (order.status()!=OrderStatus.PENDING && order.status()!=OrderStatus.PENDING_UNKNOWN)
            throw new IllegalStateException("Rejection requires a submitted order");
        orders.markRejected(order.id(), errorCode, errorMessage);
        reservations.releaseForOrder(order.id());
    }
}
