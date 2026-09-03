package com.saamp.trading.order;

import com.saamp.trading.account.AccountRepository;
import com.saamp.trading.account.Balance;
import com.saamp.trading.account.BalanceRepository;
import com.saamp.trading.account.TradingAccount;
import com.saamp.trading.common.ClientOrderIdFactory;
import com.saamp.trading.common.TradingException;
import com.saamp.trading.domain.*;
import com.saamp.trading.pricing.*;
import com.saamp.trading.provider.*;
import com.saamp.trading.reservation.ReservationRepository;
import com.saamp.trading.reservation.ReservationService;
import com.saamp.trading.risk.MarginRateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderExecutionServiceTest {

    private static final long COMPANY_ID = 42L;
    private static final long ACCOUNT_ID = 7L;
    private static final long USER_ID = 99L;
    private static final long ORDER_ID = 123L;

    @Mock AccountRepository accounts;
    @Mock BalanceRepository balances;
    @Mock PricingRepository pricingRepository;
    @Mock PricingService pricing;
    @Mock ReservationService reservations;
    @Mock ReservationRepository reservationRepository;
    @Mock MarginRateRepository marginRates;
    @Mock OrderRepository orders;
    @Mock TradingProvider provider;
    @Mock ExecutionEventHandler executionEvents;
    @Mock ExecutionGateRepository gate;

    private OrderExecutionService service;

    @BeforeEach
    void setUp() {
        lenient().when(provider.supportsSpotOrderSubmission()).thenReturn(true);
        service = new OrderExecutionService(accounts, balances, pricingRepository, pricing, reservations,
                reservationRepository, marginRates, orders, provider, executionEvents, gate);
    }

    @Test
    void purchaseBeyondAvailableFundsIsRejected() {
        stubActiveAccount(null);
        stubPreviewBasics(OrderSide.BUY, Asset.XAU, "buy-no-funds");
        doThrow(new TradingException(HttpStatus.CONFLICT, "INSUFFICIENT_AVAILABLE_BALANCE", "insufficient"))
                .when(reservations).reserve(eq(ACCOUNT_ID), eq(Asset.EUR), any(BigDecimal.class), eq(ORDER_ID));

        assertThatThrownBy(() -> service.preview(COMPANY_ID, USER_ID, request(Asset.XAU, OrderSide.BUY, "10", "buy-no-funds")))
                .isInstanceOfSatisfying(TradingException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("INSUFFICIENT_AVAILABLE_BALANCE"));

        verify(provider, never()).submitSpotOrder(any());
    }

    @Test
    void fullyCoveredSaleReservesMetalAndNoCash() {
        stubActiveAccount(null);
        stubPreviewBasics(OrderSide.SELL, Asset.XAU, "sell-covered");
        when(reservations.available(ACCOUNT_ID, Asset.XAU)).thenReturn(new BigDecimal("10"));

        OrderPreviewResponse result = service.preview(COMPANY_ID, USER_ID,
                request(Asset.XAU, OrderSide.SELL, "5", "sell-covered"));

        assertThat(result.reservedMetal()).isEqualByComparingTo("5");
        assertThat(result.reservedCash()).isEqualByComparingTo("0");
        verify(reservations).reserve(ACCOUNT_ID, Asset.XAU, new BigDecimal("5.000000"), ORDER_ID);
        verify(reservations, never()).reserve(eq(ACCOUNT_ID), eq(Asset.EUR), any(BigDecimal.class), eq(ORDER_ID));
    }

    @Test
    void partiallyUncoveredSaleReservesOnlyMarginCashForUncoveredPart() {
        stubActiveAccount(null);
        stubPreviewBasics(OrderSide.SELL, Asset.XAU, "sell-partial");
        when(reservations.available(ACCOUNT_ID, Asset.XAU)).thenReturn(new BigDecimal("4"));
        when(marginRates.currentRate(ACCOUNT_ID, Asset.XAU)).thenReturn(new BigDecimal("0.05"));

        OrderPreviewResponse result = service.preview(COMPANY_ID, USER_ID,
                request(Asset.XAU, OrderSide.SELL, "10", "sell-partial"));

        assertThat(result.reservedMetal()).isEqualByComparingTo("4");
        assertThat(result.reservedCash()).isEqualByComparingTo("630.000000");
        verify(reservations).reserve(ACCOUNT_ID, Asset.XAU, new BigDecimal("4"), ORDER_ID);
        verify(reservations).reserve(ACCOUNT_ID, Asset.EUR, new BigDecimal("630.000000"), ORDER_ID);
    }

    @Test
    void uncoveredSaleBeyondCapacityIsRejected() {
        stubActiveAccount(null);
        stubPreviewBasics(OrderSide.SELL, Asset.XAU, "sell-over-capacity");
        when(reservations.available(ACCOUNT_ID, Asset.XAU)).thenReturn(BigDecimal.ZERO);
        when(marginRates.currentRate(ACCOUNT_ID, Asset.XAU)).thenReturn(new BigDecimal("0.05"));
        doThrow(new TradingException(HttpStatus.CONFLICT, "INSUFFICIENT_AVAILABLE_BALANCE", "insufficient"))
                .when(reservations).reserve(ACCOUNT_ID, Asset.EUR, new BigDecimal("1050.000000"), ORDER_ID);

        assertThatThrownBy(() -> service.preview(COMPANY_ID, USER_ID,
                request(Asset.XAU, OrderSide.SELL, "10", "sell-over-capacity")))
                .isInstanceOfSatisfying(TradingException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("INSUFFICIENT_AVAILABLE_BALANCE"));

        verify(provider, never()).submitSpotOrder(any());
    }


    @Test
    void providerNotReadyRefusesSubmissionBeforePriceRefreshOrNetworkCall() {
        TradingOrder draft = order(ORDER_ID, COMPANY_ID, OrderStatus.DRAFT, "provider-not-ready", new BigDecimal("100.000000"));
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(draft));
        when(accounts.findByCompanyId(COMPANY_ID)).thenReturn(Optional.of(account(AccountStatus.ACTIVE, null)));
        when(gate.isOpen()).thenReturn(true);
        when(provider.supportsSpotOrderSubmission()).thenReturn(false);

        assertThatThrownBy(() -> service.submit(ORDER_ID, COMPANY_ID, USER_ID))
                .isInstanceOfSatisfying(TradingException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("PROVIDER_EXECUTION_NOT_READY"));

        verify(pricing, never()).quoteForExecution(anyLong(), any(), any());
        verify(provider, never()).submitSpotOrder(any());
        verify(orders, never()).markPending(anyLong());
    }

    @Test
    void priceMovedBeyondToleranceReleasesReservationsWithoutProviderCall() {
        stubSubmit(new BigDecimal("100.000000"), "price-moved");
        when(pricing.quoteForExecution(COMPANY_ID, Asset.XAU, Asset.EUR)).thenReturn(quote("101.000000", "101.000000"));

        assertThatThrownBy(() -> service.submit(ORDER_ID, COMPANY_ID, USER_ID))
                .isInstanceOfSatisfying(TradingException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("PRICE_MOVED"));

        verify(orders).markRejected(eq(ORDER_ID), eq("PRICE_MOVED"), anyString());
        verify(reservations).releaseForOrder(ORDER_ID);
        verify(provider, never()).submitSpotOrder(any());
    }

    @Test
    void providerInProcessMarksPendingUnknownWithoutSettlementOrRetransmission() {
        TradingOrder draft = order(ORDER_ID, COMPANY_ID, OrderStatus.DRAFT, "provider-in-process", new BigDecimal("100.000000"));
        TradingOrder unknown = order(ORDER_ID, COMPANY_ID, OrderStatus.PENDING_UNKNOWN, "provider-in-process", new BigDecimal("100.000000"));
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(draft), Optional.of(unknown));
        when(accounts.findByCompanyId(COMPANY_ID)).thenReturn(Optional.of(account(AccountStatus.ACTIVE, null)));
        when(gate.isOpen()).thenReturn(true);
        when(reservationRepository.hasActiveForOrder(ORDER_ID)).thenReturn(true);
        when(pricing.quoteForExecution(COMPANY_ID, Asset.XAU, Asset.EUR)).thenReturn(quote("100.000000", "100.000000"));
        when(pricingRepository.findAssetConfig(Asset.XAU)).thenReturn(Optional.of(assetConfig()));
        when(provider.submitSpotOrder(any())).thenReturn(new OrderAcknowledgement(
                AcknowledgementState.IN_PROCESS, draft.clOrdId(), null, null, "IN_PROCESS", "Pending"));

        TradingOrder result = service.submit(ORDER_ID, COMPANY_ID, USER_ID);

        assertThat(result.status()).isEqualTo(OrderStatus.PENDING_UNKNOWN);
        verify(orders).markPendingUnknown(ORDER_ID, "IN_PROCESS", "Pending");
        verify(executionEvents, never()).handleFilled(any(), any(), anyString(), any(), any(), anyString());
        verify(provider, times(1)).submitSpotOrder(any());
    }

    @Test
    void providerExceptionCreatesPendingUnknownAndClientRetryDoesNotRetransmit() {
        TradingOrder draft = order(ORDER_ID, COMPANY_ID, OrderStatus.DRAFT, "provider-exception", new BigDecimal("100.000000"));
        TradingOrder unknown = order(ORDER_ID, COMPANY_ID, OrderStatus.PENDING_UNKNOWN, "provider-exception", new BigDecimal("100.000000"));
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(draft), Optional.of(unknown), Optional.of(unknown));
        when(accounts.findByCompanyId(COMPANY_ID)).thenReturn(Optional.of(account(AccountStatus.ACTIVE, null)));
        when(gate.isOpen()).thenReturn(true);
        when(reservationRepository.hasActiveForOrder(ORDER_ID)).thenReturn(true);
        when(pricing.quoteForExecution(COMPANY_ID, Asset.XAU, Asset.EUR)).thenReturn(quote("100.000000", "100.000000"));
        when(pricingRepository.findAssetConfig(Asset.XAU)).thenReturn(Optional.of(assetConfig()));
        when(provider.submitSpotOrder(any())).thenThrow(new RuntimeException("timeout"));

        TradingOrder first = service.submit(ORDER_ID, COMPANY_ID, USER_ID);
        TradingOrder retry = service.submit(ORDER_ID, COMPANY_ID, USER_ID);

        assertThat(first.status()).isEqualTo(OrderStatus.PENDING_UNKNOWN);
        assertThat(retry.status()).isEqualTo(OrderStatus.PENDING_UNKNOWN);
        verify(provider, times(1)).submitSpotOrder(any());
        verify(orders).markPendingUnknown(ORDER_ID, "PROVIDER_UNCERTAIN", "timeout");
    }

    @Test
    void duplicateIdempotencyKeyReturnsExistingOrderAndCreatesNoSecondOrder() {
        String key = "same-key";
        TradingOrder existing = order(ORDER_ID, COMPANY_ID, OrderStatus.DRAFT, key, new BigDecimal("100.000000"));
        when(accounts.findByCompanyId(COMPANY_ID)).thenReturn(Optional.of(account(AccountStatus.ACTIVE, null)));
        when(orders.findByIdempotencyKey(key)).thenReturn(Optional.empty(), Optional.of(existing));
        when(pricingRepository.findAssetConfig(Asset.XAU)).thenReturn(Optional.of(assetConfig()));
        when(pricing.quoteForDisplay(COMPANY_ID, Asset.XAU, Asset.EUR)).thenReturn(quote("100.000000", "100.000000"));
        when(orders.insertDraft(anyLong(), anyLong(), any(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyString(), anyString()))
                .thenReturn(ORDER_ID);

        OrderPreviewResponse first = service.preview(COMPANY_ID, USER_ID, request(Asset.XAU, OrderSide.BUY, "1", key));
        OrderPreviewResponse second = service.preview(COMPANY_ID, USER_ID, request(Asset.XAU, OrderSide.BUY, "1", key));

        assertThat(first.orderId()).isEqualTo(ORDER_ID);
        assertThat(second.orderId()).isEqualTo(ORDER_ID);
        assertThat(existing.clOrdId()).isEqualTo(ClientOrderIdFactory.fromIdempotencyKey(key));
        verify(orders, times(1)).insertDraft(anyLong(), anyLong(), any(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyString(), anyString());
        verify(reservations, times(1)).reserve(eq(ACCOUNT_ID), eq(Asset.EUR), any(BigDecimal.class), eq(ORDER_ID));
    }

    @Test
    void expiredReservationMarksOrderExpiredAndRequiresNewQuote() {
        TradingOrder draft = order(ORDER_ID, COMPANY_ID, OrderStatus.DRAFT, "expired", new BigDecimal("100.000000"));
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(draft));
        when(accounts.findByCompanyId(COMPANY_ID)).thenReturn(Optional.of(account(AccountStatus.ACTIVE, null)));
        when(gate.isOpen()).thenReturn(true);
        when(reservationRepository.hasActiveForOrder(ORDER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.submit(ORDER_ID, COMPANY_ID, USER_ID))
                .isInstanceOfSatisfying(TradingException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("RESERVATION_EXPIRED"));

        verify(orders).markExpired(ORDER_ID);
        verify(provider, never()).submitSpotOrder(any());
    }

    @Test
    void orderFromAnotherCompanyReturns404WithoutRevealingExistence() {
        TradingOrder otherCompanyOrder = order(ORDER_ID, 111L, OrderStatus.DRAFT, "other-company", new BigDecimal("100.000000"));
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(otherCompanyOrder));

        assertThatThrownBy(() -> service.submit(ORDER_ID, COMPANY_ID, USER_ID))
                .isInstanceOfSatisfying(TradingException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getCode()).isEqualTo("ORDER_NOT_FOUND");
                });

        verify(accounts, never()).findByCompanyId(anyLong());
        verify(provider, never()).submitSpotOrder(any());
    }

    @Test
    void stalePriceAtSubmissionIsRejectedBeforeProviderCall() {
        TradingOrder draft = order(ORDER_ID, COMPANY_ID, OrderStatus.DRAFT, "stale-submit", new BigDecimal("100.000000"));
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(draft));
        when(accounts.findByCompanyId(COMPANY_ID)).thenReturn(Optional.of(account(AccountStatus.ACTIVE, null)));
        when(gate.isOpen()).thenReturn(true);
        when(reservationRepository.hasActiveForOrder(ORDER_ID)).thenReturn(true);
        when(pricing.quoteForExecution(COMPANY_ID, Asset.XAU, Asset.EUR))
                .thenThrow(new TradingException(HttpStatus.CONFLICT, "MARKET_PRICE_STALE", "stale"));

        assertThatThrownBy(() -> service.submit(ORDER_ID, COMPANY_ID, USER_ID))
                .isInstanceOfSatisfying(TradingException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("MARKET_PRICE_STALE"));

        verify(orders, never()).markPending(anyLong());
        verify(provider, never()).submitSpotOrder(any());
    }

    @Test
    void suspendedAccountIsRejectedImmediately() {
        when(accounts.findByCompanyId(COMPANY_ID)).thenReturn(Optional.of(account(AccountStatus.SUSPENDED, null)));

        assertThatThrownBy(() -> service.preview(COMPANY_ID, USER_ID,
                request(Asset.XAU, OrderSide.BUY, "1", "suspended")))
                .isInstanceOfSatisfying(TradingException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("ACCOUNT_NOT_ACTIVE"));

        verify(pricing, never()).quoteForDisplay(anyLong(), any(), any());
        verify(provider, never()).submitSpotOrder(any());
    }

    @Test
    void positionLimitUsesExecutionFreshPriceAndPropagatesMarketPriceStale() {
        TradingAccount limited = account(AccountStatus.ACTIVE, new BigDecimal("1000000"));
        when(accounts.findByCompanyId(COMPANY_ID)).thenReturn(Optional.of(limited));
        when(orders.findByIdempotencyKey("position-stale")).thenReturn(Optional.empty());
        when(pricingRepository.findAssetConfig(Asset.XAU)).thenReturn(Optional.of(assetConfig()));
        when(pricing.quoteForDisplay(COMPANY_ID, Asset.XAU, Asset.EUR)).thenReturn(quote("100.000000", "100.000000"));
        when(balances.find(ACCOUNT_ID, Asset.XAU)).thenReturn(Optional.of(new Balance(ACCOUNT_ID, Asset.XAU, BigDecimal.ONE, OffsetDateTime.now())));
        when(pricing.quoteForExecution(COMPANY_ID, Asset.XAU, Asset.EUR))
                .thenThrow(new TradingException(HttpStatus.CONFLICT, "MARKET_PRICE_STALE", "stale"));

        assertThatThrownBy(() -> service.preview(COMPANY_ID, USER_ID,
                request(Asset.XAU, OrderSide.BUY, "1", "position-stale")))
                .isInstanceOfSatisfying(TradingException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("MARKET_PRICE_STALE"));

        verify(pricing).quoteForExecution(COMPANY_ID, Asset.XAU, Asset.EUR);
        verify(orders, never()).insertDraft(anyLong(), anyLong(), any(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyString(), anyString());
    }

    private void stubActiveAccount(BigDecimal positionLimit) {
        when(accounts.findByCompanyId(COMPANY_ID)).thenReturn(Optional.of(account(AccountStatus.ACTIVE, positionLimit)));
    }

    private void stubPreviewBasics(OrderSide side, Asset asset, String key) {
        when(orders.findByIdempotencyKey(key)).thenReturn(Optional.empty());
        when(pricingRepository.findAssetConfig(asset)).thenReturn(Optional.of(assetConfig()));
        when(pricing.quoteForDisplay(COMPANY_ID, asset, Asset.EUR)).thenReturn(quote("100.000000", "100.000000"));
        when(orders.insertDraft(anyLong(), anyLong(), any(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyString(), anyString()))
                .thenReturn(ORDER_ID);
    }

    private void stubSubmit(BigDecimal indicativeClientPrice, String key) {
        TradingOrder draft = order(ORDER_ID, COMPANY_ID, OrderStatus.DRAFT, key, indicativeClientPrice);
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(draft));
        when(accounts.findByCompanyId(COMPANY_ID)).thenReturn(Optional.of(account(AccountStatus.ACTIVE, null)));
        when(gate.isOpen()).thenReturn(true);
        when(reservationRepository.hasActiveForOrder(ORDER_ID)).thenReturn(true);
        when(pricingRepository.findAssetConfig(Asset.XAU)).thenReturn(Optional.of(assetConfig()));
    }

    private TradingAccount account(AccountStatus status, BigDecimal positionLimit) {
        return new TradingAccount(ACCOUNT_ID, COMPANY_ID, Asset.EUR, status, null, positionLimit, null, 1,
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    private AssetConfig assetConfig() {
        return new AssetConfig(Asset.XAU, new BigDecimal("0.000001"), 6, new BigDecimal("0.002000"), true);
    }

    private ClientQuote quote(String buy, String sell) {
        BigDecimal buyPrice = new BigDecimal(buy);
        BigDecimal sellPrice = new BigDecimal(sell);
        return new ClientQuote(Asset.XAU, "XAUEUR", new BigDecimal("99.000000"), new BigDecimal("100.000000"),
                buyPrice, sellPrice, buyPrice, sellPrice,
                new BigDecimal("0.001000"), new BigDecimal("0.001000"), 1, OffsetDateTime.now());
    }

    private OrderPreviewRequest request(Asset asset, OrderSide side, String qty, String key) {
        return new OrderPreviewRequest(asset, side, new BigDecimal(qty), QuantityUnit.OZ, key);
    }

    private TradingOrder order(long id, long companyId, OrderStatus status, String key, BigDecimal indicativeClientPrice) {
        return new TradingOrder(id, ACCOUNT_ID, companyId, null, Asset.XAU, "XAUEUR", OrderSide.BUY, OrderType.SPOT,
                BigDecimal.ONE, QuantityUnit.OZ, BigDecimal.ONE, status,
                new BigDecimal("100.000000"), indicativeClientPrice, indicativeClientPrice,
                null, null, null, new BigDecimal("0.001000"), 1, null, null,
                key, ClientOrderIdFactory.fromIdempotencyKey(key), null, null, null, 0,
                null, null, null, OffsetDateTime.now(), null, null);
    }
}
