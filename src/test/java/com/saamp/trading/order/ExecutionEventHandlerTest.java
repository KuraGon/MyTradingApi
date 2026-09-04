package com.saamp.trading.order;

import com.saamp.trading.account.TradingAccount;
import com.saamp.trading.common.ClientOrderIdFactory;
import com.saamp.trading.domain.*;
import com.saamp.trading.ledger.LedgerService;
import com.saamp.trading.pricing.AssetConfig;
import com.saamp.trading.pricing.ClientQuote;
import com.saamp.trading.pricing.PricingRepository;
import com.saamp.trading.reservation.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecutionEventHandlerTest {

    @Mock PricingRepository pricing;
    @Mock LedgerService ledger;
    @Mock OrderRepository orders;
    @Mock ReservationService reservations;

    private ExecutionEventHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ExecutionEventHandler(pricing, ledger, orders, reservations);
    }

    @Test
    void processedExecutionPostsBothLedgerLegsOnceAndConsumesReservations() {
        TradingOrder order = order();
        TradingAccount account = account();
        ClientQuote quote = quote();
        when(pricing.findAssetConfig(Asset.XAU)).thenReturn(Optional.of(
                new AssetConfig(Asset.XAU, new BigDecimal("0.000001"), 6, new BigDecimal("0.002000"), true)));

        handler.handleFilled(order, account, "EX-100", new BigDecimal("100.000000"), quote, "system:test");

        verify(ledger, times(1)).postTrade(eq(account.id()), eq(Asset.XAU), eq(new BigDecimal("1")), eq(Asset.EUR),
                eq(new BigDecimal("-100.10")), eq(order.id()), eq("system:test"));
        verify(orders, times(1)).markFilled(eq(order.id()), eq("EX-100"), eq(new BigDecimal("100.000000")),
                any(BigDecimal.class), eq(new BigDecimal("100.100000")), eq(new BigDecimal("0.001000")),
                eq(1), eq(new BigDecimal("0.100000")), eq(new BigDecimal("100.10")));
        verify(reservations, times(1)).consumeForOrder(order.id());
    }

    @Test
    void cashLedgerUsesPublishedGrossAmountRoundedToCurrencyScale() {
        TradingOrder order = order();
        TradingAccount account = account();
        ClientQuote quote = new ClientQuote(Asset.XAU, "XAUEUR",
                new BigDecimal("3000.000000"), new BigDecimal("3001.000000"),
                new BigDecimal("3010.003000"), new BigDecimal("2991.000000"),
                new BigDecimal("3010.003000"), new BigDecimal("2991.000000"),
                new BigDecimal("0.003000"), new BigDecimal("0.003000"), 1, OffsetDateTime.now());
        when(pricing.findAssetConfig(Asset.XAU)).thenReturn(Optional.of(
                new AssetConfig(Asset.XAU, new BigDecimal("0.000001"), 6, new BigDecimal("0.002000"), true)));

        handler.handleFilled(order, account, "EX-ROUND", new BigDecimal("3001.000000"), quote, "system:test");

        verify(ledger).postTrade(eq(account.id()), eq(Asset.XAU), eq(new BigDecimal("1")), eq(Asset.EUR),
                eq(new BigDecimal("-3010.00")), eq(order.id()), eq("system:test"));
        verify(orders).markFilled(eq(order.id()), eq("EX-ROUND"), eq(new BigDecimal("3001.000000")),
                any(BigDecimal.class), eq(new BigDecimal("3010.003000")), eq(new BigDecimal("0.003000")),
                eq(1), eq(new BigDecimal("9.003000")), eq(new BigDecimal("3010.00")));
        verify(reservations).consumeForOrder(order.id());
    }

    @Test
    void failedExecutionRejectsOrderAndReleasesReservations() {
        TradingOrder order = order();

        handler.handleRejected(order, "PMX_REJECTED", "Rejected");

        verify(orders).markRejected(order.id(), "PMX_REJECTED", "Rejected");
        verify(reservations).releaseForOrder(order.id());
        verifyNoInteractions(ledger);
    }

    private TradingOrder order() {
        String key = "execution-handler";
        return new TradingOrder(10L, 5L, 42L, null, Asset.XAU, "XAUEUR", OrderSide.BUY, OrderType.SPOT,
                BigDecimal.ONE, QuantityUnit.OZ, BigDecimal.ONE, OrderStatus.PENDING_UNKNOWN,
                new BigDecimal("100"), new BigDecimal("100.1"), new BigDecimal("100.1"),
                null, null, null, new BigDecimal("0.001"), 1, null, null,
                key, ClientOrderIdFactory.fromIdempotencyKey(key), null, null, null, 0,
                null, null, null, OffsetDateTime.now(), OffsetDateTime.now(), null);
    }

    private TradingAccount account() {
        return new TradingAccount(5L, 42L, Asset.EUR, AccountStatus.ACTIVE, null, null, null, 1,
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    private ClientQuote quote() {
        return new ClientQuote(Asset.XAU, "XAUEUR", new BigDecimal("99"), new BigDecimal("100"),
                new BigDecimal("100.1"), new BigDecimal("98.901"), new BigDecimal("100.1"), new BigDecimal("98.901"),
                new BigDecimal("0.001000"), new BigDecimal("0.001000"), 1, OffsetDateTime.now());
    }
}
