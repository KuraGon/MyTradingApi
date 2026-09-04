package com.saamp.trading.order;

import com.saamp.trading.account.TradingAccount;
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
 * Inbound execution event handler. Provider calls and DB settlement are intentionally separated:
 * no database transaction is held open while an irreversible external trade is being submitted.
 */
@Service
public class ExecutionEventHandler {
    private final PricingRepository pricing;
    private final LedgerService ledger;
    private final OrderRepository orders;
    private final ReservationService reservations;

    public ExecutionEventHandler(PricingRepository pricing, LedgerService ledger, OrderRepository orders,
                                 ReservationService reservations) {
        this.pricing = pricing;
        this.ledger = ledger;
        this.orders = orders;
        this.reservations = reservations;
    }

    @Transactional
    public void handleFilled(TradingOrder order, TradingAccount account, String executionId,
                             BigDecimal marketRate, ClientQuote quoteAtSubmission, String actor) {
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

    @Transactional
    public void handleRejected(TradingOrder order, String errorCode, String errorMessage) {
        orders.markRejected(order.id(), errorCode, errorMessage);
        reservations.releaseForOrder(order.id());
    }
}
