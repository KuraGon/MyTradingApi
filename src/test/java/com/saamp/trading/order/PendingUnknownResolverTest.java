package com.saamp.trading.order;

import com.saamp.trading.account.AccountRepository;
import com.saamp.trading.account.TradingAccount;
import com.saamp.trading.common.ClientOrderIdFactory;
import com.saamp.trading.domain.*;
import com.saamp.trading.pricing.ClientQuote;
import com.saamp.trading.pricing.PricingService;
import com.saamp.trading.provider.ExecutionReport;
import com.saamp.trading.provider.ExecutionState;
import com.saamp.trading.provider.TradingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PendingUnknownResolverTest {

    @Mock OrderRepository orders;
    @Mock AccountRepository accounts;
    @Mock TradingProvider provider;
    @Mock PricingService pricing;
    @Mock ExecutionEventHandler events;

    private PendingUnknownResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new PendingUnknownResolver(orders, accounts, provider, pricing, events);
    }

    @Test
    void inProcessThenProcessedSettlesExactlyOnceWithoutRetransmission() {
        TradingOrder order = order("processed");
        TradingAccount account = account();
        ClientQuote quote = quote();
        when(orders.findPendingUnknownDue(50)).thenReturn(List.of(order));
        when(provider.queryRequestStatus(order.clOrdId())).thenReturn(Optional.of(
                new ExecutionReport(ExecutionState.PROCESSED, order.clOrdId(), "EX-1", new BigDecimal("100.25"), null, null)));
        when(accounts.findById(order.accountId())).thenReturn(Optional.of(account));
        when(pricing.quoteForExecution(order.companyId(), order.asset(), account.baseCurrency())).thenReturn(quote);

        resolver.resolveDue();

        verify(provider, times(1)).queryRequestStatus(order.clOrdId());
        verify(provider, never()).submitSpotOrder(any());
        verify(events, times(1)).handleFilled(eq(order), eq(account), eq("EX-1"), eq(new BigDecimal("100.25")), eq(quote), eq("system:request-status"));
        verify(orders, never()).scheduleNextResolution(anyLong(), anyInt(), any(), anyBoolean());
    }

    @Test
    void inProcessThenFailedRejectsAndReleasesViaExecutionHandler() {
        TradingOrder order = order("failed");
        when(orders.findPendingUnknownDue(50)).thenReturn(List.of(order));
        when(provider.queryRequestStatus(order.clOrdId())).thenReturn(Optional.of(
                new ExecutionReport(ExecutionState.FAILED, order.clOrdId(), null, null, "PMX_REJECTED", "Rejected")));

        resolver.resolveDue();

        verify(provider, times(1)).queryRequestStatus(order.clOrdId());
        verify(provider, never()).submitSpotOrder(any());
        verify(events, times(1)).handleRejected(order, "PMX_REJECTED", "Rejected");
        verify(orders, never()).scheduleNextResolution(anyLong(), anyInt(), any(), anyBoolean());
    }

    private TradingOrder order(String key) {
        return new TradingOrder(10L, 5L, 42L, null, Asset.XAU, "XAUEUR", OrderSide.BUY, OrderType.SPOT,
                BigDecimal.ONE, QuantityUnit.OZ, BigDecimal.ONE, OrderStatus.PENDING_UNKNOWN,
                new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"),
                null, null, null, new BigDecimal("0.001"), 1, null, null,
                key, ClientOrderIdFactory.fromIdempotencyKey(key), null, "IN_PROCESS", null, 0,
                OffsetDateTime.now().minusSeconds(2), OffsetDateTime.now().minusSeconds(1), null,
                OffsetDateTime.now().minusMinutes(1), OffsetDateTime.now().minusMinutes(1), null);
    }

    private TradingAccount account() {
        return new TradingAccount(5L, 42L, Asset.EUR, AccountStatus.ACTIVE, null, null, null, 1,
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    private ClientQuote quote() {
        return new ClientQuote(Asset.XAU, "XAUEUR", new BigDecimal("99"), new BigDecimal("100"),
                new BigDecimal("100.1"), new BigDecimal("98.9"), new BigDecimal("100.1"), new BigDecimal("98.9"),
                new BigDecimal("0.001"), new BigDecimal("0.001"), 1, OffsetDateTime.now());
    }
}
