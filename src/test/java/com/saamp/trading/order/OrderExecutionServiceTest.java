package com.saamp.trading.order;

import com.saamp.trading.account.*;
import com.saamp.trading.common.TradingException;
import com.saamp.trading.config.TradingProperties;
import com.saamp.trading.domain.*;
import com.saamp.trading.pricing.*;
import com.saamp.trading.provider.*;
import com.saamp.trading.reservation.*;
import com.saamp.trading.domain.TradingMode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Vérifie la frontière entre admission atomique et transmission irréversible. */
class OrderExecutionServiceTest {
    AccountRepository accounts=mock(AccountRepository.class);
    BalanceRepository balances=mock(BalanceRepository.class);
    DemoBalanceRepository demoBalances=mock(DemoBalanceRepository.class);
    PricingRepository configs=mock(PricingRepository.class);
    PricingService pricing=mock(PricingService.class);
    ReservationRepository reservations=mock(ReservationRepository.class);
    OrderRepository orders=mock(OrderRepository.class);
    TradingProvider provider=mock(TradingProvider.class);
    ExecutionEventHandler events=mock(ExecutionEventHandler.class);
    ExecutionGateRepository gate=mock(ExecutionGateRepository.class);
    OrderCapacityService capacity=mock(OrderCapacityService.class);
    OrderExecutionService service;
    TradingOrder order;
    TradingAccount account;
    ClientQuote quote;
    TradingProperties properties;

    @BeforeEach void setup() {
        account=new TradingAccount(7,42,Asset.EUR,AccountStatus.ACTIVE,null,null,null,1,OffsetDateTime.now(),OffsetDateTime.now());
        order=mock(TradingOrder.class);
        when(order.id()).thenReturn(123L); when(order.accountId()).thenReturn(7L); when(order.companyId()).thenReturn(42L);
        when(order.asset()).thenReturn(Asset.XAU); when(order.side()).thenReturn(OrderSide.BUY);
        when(order.quantityOz()).thenReturn(BigDecimal.ONE); when(order.status()).thenReturn(OrderStatus.DRAFT);
        when(order.indicativeClientPrice()).thenReturn(new BigDecimal("100"));
        when(order.tradingMode()).thenReturn(TradingMode.LIVE);
        when(order.createdAt()).thenReturn(OffsetDateTime.now());
        when(order.clOrdId()).thenReturn("CL-123"); when(order.pair()).thenReturn("XAUEUR");
        quote=new ClientQuote(Asset.XAU,"XAUEUR",new BigDecimal("99"),new BigDecimal("100"),
                new BigDecimal("100"),new BigDecimal("99"),new BigDecimal("100"),new BigDecimal("99"),
                BigDecimal.ZERO,BigDecimal.ZERO,1,OffsetDateTime.now());
        when(accounts.findByCompanyId(42)).thenReturn(Optional.of(account));
        when(accounts.lockById(7)).thenReturn(Optional.of(account));
        when(orders.findById(123)).thenReturn(Optional.of(order)); when(orders.lockById(123)).thenReturn(Optional.of(order));
        when(gate.isOpen()).thenReturn(true); when(provider.supportsSpotOrderSubmission()).thenReturn(true);
        when(pricing.quoteForExecution(42,Asset.XAU,Asset.EUR)).thenReturn(quote);
        when(configs.findAssetConfig(Asset.XAU)).thenReturn(Optional.of(new AssetConfig(Asset.XAU,new BigDecimal("0.000001"),6,new BigDecimal("0.002"),true)));
        when(orders.markPending(123)).thenReturn(1);
        when(provider.submitSpotOrder(any())).thenReturn(new OrderAcknowledgement(AcknowledgementState.IN_PROCESS,"CL-123",null,null,null,null));
        properties = new TradingProperties();
        when(demoBalances.findAll(7L)).thenReturn(List.of(new Balance(7L, Asset.EUR, new BigDecimal("1000"), OffsetDateTime.now())));
        service=new OrderExecutionService(accounts,new EffectiveBalanceService(balances,accounts,null,null,new EffectiveBalanceProperties(),demoBalances,new SimpleMeterRegistry()),configs,pricing,reservations,orders,provider,events,gate,capacity,
                properties,mock(PlatformTransactionManager.class));
    }

    @Test void submitRecalculatesBeforeConditionalTransitionAndProvider() {
        service.submit(123,42,99);
        var sequence=inOrder(pricing,accounts,orders,capacity,provider);
        sequence.verify(pricing).quoteForExecution(42,Asset.XAU,Asset.EUR);
        sequence.verify(accounts).lockById(7);
        sequence.verify(orders).lockById(123);
        sequence.verify(capacity).reserve(eq(account),eq(order),anyMap(),anyMap(),any(),any(),eq(true),any());
        sequence.verify(orders).markPending(123);
        sequence.verify(provider).submitSpotOrder(any());
    }

    @Test void changedCapacityNeverTransmits() {
        when(capacity.reserve(any(),any(),anyMap(),anyMap(),any(),any(),eq(true),any()))
                .thenThrow(new TradingException(org.springframework.http.HttpStatus.CONFLICT,"INSUFFICIENT_FREE_EQUITY","test"));
        assertThatThrownBy(()->service.submit(123,42,99)).isInstanceOf(TradingException.class);
        verify(provider,never()).submitSpotOrder(any()); verify(orders,never()).markPending(anyLong());
    }

    @Test void priceMovedReleasesBeforeAnyTransmission() {
        when(order.indicativeClientPrice()).thenReturn(new BigDecimal("90"));
        assertThatThrownBy(()->service.submit(123,42,99)).isInstanceOfSatisfying(TradingException.class,
                e->assertThat(e.getCode()).isEqualTo("PRICE_MOVED"));
        verify(reservations).releaseForOrder(123); verifyNoInteractions(capacity); verify(provider,never()).submitSpotOrder(any());
    }

    @Test void expiredPreviewNeverTransmits() {
        when(reservations.findEarliestExpiryForOrder(123)).thenReturn(Optional.of(OffsetDateTime.now().minusMinutes(1)));
        assertThatThrownBy(()->service.submit(123,42,99)).isInstanceOfSatisfying(TradingException.class,
                e->assertThat(e.getCode()).isEqualTo("RESERVATION_EXPIRED"));
        verify(orders).markExpired(123); verify(reservations).expireForOrder(123); verify(provider,never()).submitSpotOrder(any());
    }

    @Test void anotherCompanyRemains404() {
        assertThatThrownBy(()->service.submit(123,43,99)).isInstanceOfSatisfying(TradingException.class,
                e->assertThat(e.getStatus().value()).isEqualTo(404));
        verifyNoInteractions(pricing); verify(provider,never()).submitSpotOrder(any());
    }

    @Test void providerNotReadyDoesNotFetchPriceOrSend() {
        when(provider.supportsSpotOrderSubmission()).thenReturn(false);
        assertThatThrownBy(()->service.submit(123,42,99)).isInstanceOfSatisfying(TradingException.class,
                e->assertThat(e.getCode()).isEqualTo("PROVIDER_EXECUTION_NOT_READY"));
        verifyNoInteractions(pricing); verify(provider,never()).submitSpotOrder(any());
    }

    @Test void demoOrderCannotUseALiveAccount() {
        properties.getDemo().setEnabled(true);
        properties.getProvider().setMode("PMXCONNECT");
        when(order.tradingMode()).thenReturn(TradingMode.DEMO);

        assertThatThrownBy(() -> service.submit(123,42,99,TradingMode.DEMO))
                .isInstanceOfSatisfying(TradingException.class,
                        error -> assertThat(error.getCode()).isEqualTo("ACCOUNT_TRADING_MODE_MISMATCH"));
        verifyNoInteractions(pricing, capacity);
        verify(provider, never()).submitSpotOrder(any());
    }

    @Test void demoUsesLocalSimulatorEvenWithPmxLiveProviderAndClosedLiveGate() {
        properties.getDemo().setEnabled(true);
        when(order.tradingMode()).thenReturn(TradingMode.DEMO);

        properties.getProvider().setMode("PMXCONNECT");
        when(gate.isOpen()).thenReturn(false);
        account=new TradingAccount(7,42,Asset.EUR,AccountStatus.ACTIVE,null,null,null,
                null,null,null,1,OffsetDateTime.now(),OffsetDateTime.now(),TradingMode.DEMO);
        when(accounts.findByCompanyId(42)).thenReturn(Optional.of(account));
        when(accounts.lockById(7)).thenReturn(Optional.of(account));
        when(configs.findMarketPrice("XAUEUR")).thenReturn(Optional.of(new MarketPrice("XAUEUR",
                new BigDecimal("99"),new BigDecimal("100"),new BigDecimal("99.5"),OffsetDateTime.now(),"PMXCONNECT",OffsetDateTime.now())));
        service.submit(123,42,99,TradingMode.DEMO);

        verifyNoInteractions(provider);
        verify(events).handleFilled(eq(order),eq(account),startsWith("SIM-"),eq(new BigDecimal("100")),eq(quote),eq("user:99"));
    }

    @Test void liveDraftCannotBeSubmittedAsDemo() {
        properties.getDemo().setEnabled(true);

        assertThatThrownBy(() -> service.submit(123,42,99,TradingMode.DEMO))
                .isInstanceOfSatisfying(TradingException.class,
                        error -> assertThat(error.getCode()).isEqualTo("ORDER_TRADING_MODE_MISMATCH"));
        verify(provider, never()).submitSpotOrder(any());
    }

    @Test void stalePriceDoesNotReserveOrSend() {
        when(pricing.quoteForExecution(42,Asset.XAU,Asset.EUR)).thenThrow(new TradingException(
                org.springframework.http.HttpStatus.CONFLICT,"MARKET_PRICE_STALE","test"));
        assertThatThrownBy(()->service.submit(123,42,99)).isInstanceOf(TradingException.class);
        verifyNoInteractions(capacity); verify(provider,never()).submitSpotOrder(any());
    }

    @Test void pendingUnknownIsNeverResent() {
        when(order.status()).thenReturn(OrderStatus.PENDING_UNKNOWN);
        assertThat(service.submit(123,42,99)).isSameAs(order);
        verifyNoInteractions(pricing); verify(provider,never()).submitSpotOrder(any());
    }

    @Test void providerFailureRetainsReservationsAndBecomesUnknown() {
        when(provider.submitSpotOrder(any())).thenThrow(new IllegalStateException("timeout"));
        service.submit(123,42,99);
        verify(orders).markPendingUnknown(eq(123L),eq("PROVIDER_UNCERTAIN"),anyString());
        verify(reservations,never()).releaseForOrder(anyLong());
    }

    @Test void rejectedProviderUsesTransactionalRejection() {
        when(provider.submitSpotOrder(any())).thenReturn(new OrderAcknowledgement(AcknowledgementState.REJECTED,"CL",null,null,"REJECT","test"));
        service.submit(123,42,99);
        verify(events).handleRejected(order,"REJECT","test");
    }

    @Test void suspendedAccountIsRejectedBeforeQuotesAndSubmission() {
        var suspended=new TradingAccount(7,42,Asset.EUR,AccountStatus.SUSPENDED,null,null,null,1,null,null);
        when(accounts.findByCompanyId(42)).thenReturn(Optional.of(suspended));
        assertThatThrownBy(()->service.submit(123,42,99)).isInstanceOfSatisfying(TradingException.class,
                e->assertThat(e.getCode()).isEqualTo("ACCOUNT_NOT_ACTIVE"));
        verifyNoInteractions(pricing,capacity);
        verify(provider,never()).submitSpotOrder(any());
    }

    @Test void accountSuspendedDuringQuoteIsRejectedUnderAccountLock() {
        when(accounts.lockById(7)).thenReturn(Optional.of(new TradingAccount(7,42,Asset.EUR,AccountStatus.SUSPENDED,null,null,null,1,null,null)));
        assertThatThrownBy(()->service.submit(123,42,99)).isInstanceOfSatisfying(TradingException.class,
                e->assertThat(e.getCode()).isEqualTo("ACCOUNT_NOT_ACTIVE"));
        verifyNoInteractions(capacity);
        verify(provider,never()).submitSpotOrder(any());
    }

    @Test void executionGateStillBlocksBeforeQuote() {
        when(gate.isOpen()).thenReturn(false);
        assertThatThrownBy(()->service.submit(123,42,99)).isInstanceOfSatisfying(TradingException.class,
                e->assertThat(e.getCode()).isEqualTo("EXECUTION_BLOCKED"));
        verifyNoInteractions(pricing,capacity);
        verify(provider,never()).submitSpotOrder(any());
    }

    @Test void previewPositionLimitStillRequiresExecutionFreshnessBeforeWriting() {
        when(accounts.findByCompanyId(42)).thenReturn(Optional.of(new TradingAccount(7,42,Asset.EUR,AccountStatus.ACTIVE,null,new BigDecimal("1000"),null,1,null,null)));
        when(pricing.quoteForDisplay(42,Asset.XAU,Asset.EUR)).thenReturn(quote);
        when(pricing.quoteForExecution(42,Asset.XAU,Asset.EUR)).thenThrow(new TradingException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"MARKET_PRICE_STALE","test"));
        assertThatThrownBy(()->service.preview(42,99,previewRequest(BigDecimal.ONE))).isInstanceOfSatisfying(TradingException.class,
                e->assertThat(e.getCode()).isEqualTo("MARKET_PRICE_STALE"));
        verify(accounts,never()).lockById(anyLong());
        verifyNoInteractions(capacity);
    }

    @Test void disabledAssetAndMinimumQuantityStillRejectBeforeDraftInsertion() {
        when(pricing.quoteForDisplay(42,Asset.XAU,Asset.EUR)).thenReturn(quote);
        when(configs.findAssetConfig(Asset.XAU)).thenReturn(Optional.of(new AssetConfig(Asset.XAU,BigDecimal.ONE,6,new BigDecimal("0.002"),false)));
        assertThatThrownBy(()->service.preview(42,99,previewRequest(BigDecimal.ONE))).isInstanceOfSatisfying(TradingException.class,
                e->assertThat(e.getCode()).isEqualTo("ASSET_DISABLED"));
        when(configs.findAssetConfig(Asset.XAU)).thenReturn(Optional.of(new AssetConfig(Asset.XAU,BigDecimal.ONE,6,new BigDecimal("0.002"),true)));
        assertThatThrownBy(()->service.preview(42,99,previewRequest(new BigDecimal("0.5")))).isInstanceOfSatisfying(TradingException.class,
                e->assertThat(e.getCode()).isEqualTo("QUANTITY_TOO_SMALL"));
        verifyNoInteractions(capacity);
        verify(orders,never()).lockById(anyLong());
    }

    private OrderPreviewRequest previewRequest(BigDecimal quantity) {
        return new OrderPreviewRequest(Asset.XAU,OrderSide.BUY,quantity,QuantityUnit.OZ,"preview-control");
    }

    @Test void lostConditionalTransitionNeverSends() {
        when(orders.markPending(123)).thenReturn(0);
        assertThatThrownBy(()->service.submit(123,42,99)).isInstanceOf(IllegalStateException.class);
        verify(provider,never()).submitSpotOrder(any());
    }
}
