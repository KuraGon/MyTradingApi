package com.saamp.trading.integration;

import com.saamp.trading.account.*;
import com.saamp.trading.common.TradingException;
import com.saamp.trading.domain.*;
import com.saamp.trading.ledger.LedgerService;
import com.saamp.trading.order.*;
import com.saamp.trading.pricing.*;
import com.saamp.trading.provider.*;
import com.saamp.trading.reservation.*;
import com.saamp.trading.risk.MarginRateRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Exerce l'admission et les règlements concurrents sur PostgreSQL réel. */
@org.springframework.context.annotation.Import(com.saamp.trading.support.LocalPostgres.Context.class)
@SpringBootTest(properties={
        "trading.provider.mode=SIMULATED",
        "trading.as400.enabled=false",
        "trading.as400.jdbc-url=",
        "trading.reconciliation.enabled=false"
})
class RuleBTradingIntegrationTest {
    // Empêche tout batch automatique de modifier l'environnement local pendant ces scénarios explicites.
    @org.springframework.test.context.bean.override.mockito.MockitoBean(
            name="org.springframework.context.annotation.internalScheduledAnnotationProcessor")
    org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor scheduling;

    private static final AtomicLong IDS=new AtomicLong(System.currentTimeMillis());
    @MockitoSpyBean JdbcTemplate jdbc;
    @Autowired LedgerService ledger;
    @Autowired OrderExecutionService service;
    @Autowired ExecutionEventHandler events;
    @Autowired OrderRepository orders;
    @Autowired AccountRepository accounts;
    @MockitoSpyBean ReservationRepository reservations;
    @Autowired ReservationService reservationService;
    @MockitoSpyBean OrderCapacityService capacity;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired com.saamp.trading.transfer.TransferService transfers;
    @MockitoBean PricingService pricing;
    @MockitoBean TradingProvider provider;
    @MockitoBean PendingUnknownResolver resolver;
    @MockitoSpyBean MarginRateRepository margins;
    long accountId,companyId;
    ClientQuote quote;
    @Autowired com.saamp.trading.config.TradingProperties properties;
    private volatile CountDownLatch expiryCandidatesRead;

    @BeforeEach void setup() {
        expiryCandidatesRead=null;
        // Ne borne que le compte ; la sélection SQL et les écritures d'expiration restent réelles.
        doAnswer(this::scopedExpiryCandidates).when(jdbc).query(startsWith("SELECT DISTINCT o.account_id,o.id"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<long[]>>any());
        doAnswer(this::scopedExpiryCandidates).when(jdbc).query(startsWith("SELECT DISTINCT o.account_id,o.id"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<long[]>>any(),any(Object[].class));
        companyId=IDS.incrementAndGet();
        accountId=jdbc.queryForObject("INSERT INTO trading_account(company_id,base_currency,status) VALUES (?,'EUR','ACTIVE') RETURNING id",Long.class,companyId);
        jdbc.update("INSERT INTO trading_margin_rate(account_id,asset,rate) VALUES (?,'XAU',0.05)",accountId);
        quote=new ClientQuote(Asset.XAU,"XAUEUR",b("100"),b("100"),b("100"),b("100"),b("100"),b("100"),
                BigDecimal.ZERO,BigDecimal.ZERO,1,OffsetDateTime.now());
        when(pricing.quoteForDisplay(companyId,Asset.XAU,Asset.EUR)).thenReturn(quote);
        when(pricing.quoteForExecution(companyId,Asset.XAU,Asset.EUR)).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return quote;
        });
        when(provider.supportsSpotOrderSubmission()).thenReturn(true);
        when(provider.submitSpotOrder(any())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new OrderAcknowledgement(AcknowledgementState.IN_PROCESS,call.<SpotOrderRequest>getArgument(0).clientOrderId(),null,null,null,null);
        });
    }

    @Test void reverseFillMustKeepUnbackedTransmittedCloseCovered() {
        fund("0","10");
        var a=preview(OrderSide.SELL,"10");
        service.submit(a.orderId(),companyId,1);
        assertKind(a.orderId(),"POSITION_CLOSE","10");
        assertKind(a.orderId(),"RISK","0");
        var second=preview(OrderSide.SELL,"10");
        service.submit(second.orderId(),companyId,1);
        assertKind(second.orderId(),"POSITION_CLOSE","0");
        assertKind(second.orderId(),"RISK","52");
        assertThat(orders.findById(second.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PENDING_UNKNOWN);
        events.handleFilled(orders.findById(second.orderId()).orElseThrow(),accounts.findById(accountId).orElseThrow(),
                "REVERSE-B",b("100"),quote,"test:reverse-fill");
        assertThat(balancesQuantity(Asset.EUR)).isEqualByComparingTo("1000");
        assertThat(balancesQuantity(Asset.XAU)).isZero();
        assertThat(orders.findById(second.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.FILLED);
        assertThat(jdbc.queryForList("SELECT status FROM trading_reservation WHERE order_id=?",String.class,second.orderId()))
                .isNotEmpty().containsOnly("CONSUMED");
        assertThat(orders.findById(a.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PENDING_UNKNOWN);
        assertKind(a.orderId(),"POSITION_CLOSE","10");
        assertCode(()->preview(OrderSide.SELL,"192"),"INSUFFICIENT_FREE_EQUITY");
        var covered=preview(OrderSide.SELL,"182");
        assertKind(covered.orderId(),"RISK","946.4");
        service.submit(covered.orderId(),companyId,1);
        for (long id:List.of(covered.orderId(),a.orderId()))
            events.handleFilled(orders.findById(id).orElseThrow(),accounts.findById(accountId).orElseThrow(),
                    "REVERSE-COVERED-"+id,b("100"),quote,"test:reverse-fill");
        assertThat(balancesQuantity(Asset.EUR)).isEqualByComparingTo("20200");
        assertThat(balancesQuantity(Asset.XAU)).isEqualByComparingTo("-192");
        assertThat(balancesQuantity(Asset.EUR).add(balancesQuantity(Asset.XAU).multiply(b("100")))
                .subtract(b("192").multiply(b("100")).multiply(margins.currentRate(accountId,Asset.XAU))))
                .isEqualByComparingTo("40");

    }

    @Test void normalFillOrderKeepsRemainingOpeningRiskAndSettlesCorrectly() {
        fund("0","10");
        var a=preview(OrderSide.SELL,"10"); service.submit(a.orderId(),companyId,1);
        var second=preview(OrderSide.SELL,"10"); service.submit(second.orderId(),companyId,1);
        events.handleFilled(orders.findById(a.orderId()).orElseThrow(),accounts.findById(accountId).orElseThrow(),
                "NORMAL-A",b("100"),quote,"test:normal-fill");
        assertKind(second.orderId(),"RISK","52");
        assertThat(orders.findById(second.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PENDING_UNKNOWN);
        assertCode(()->preview(OrderSide.SELL,"192"),"INSUFFICIENT_FREE_EQUITY");
        var covered=preview(OrderSide.SELL,"182");
        assertKind(covered.orderId(),"RISK","946.4");
        events.handleFilled(orders.findById(second.orderId()).orElseThrow(),accounts.findById(accountId).orElseThrow(),
                "NORMAL-B",b("100"),quote,"test:normal-fill");
        assertThat(balancesQuantity(Asset.EUR)).isEqualByComparingTo("2000");
        assertThat(balancesQuantity(Asset.XAU)).isEqualByComparingTo("-10");
        for (long id:List.of(a.orderId(),second.orderId())) {
            assertThat(orders.findById(id).orElseThrow().status()).isEqualTo(OrderStatus.FILLED);
            assertThat(jdbc.queryForList("SELECT status FROM trading_reservation WHERE order_id=?",String.class,id))
                    .isNotEmpty().containsOnly("CONSUMED");
        }
    }

    @Test void residualPositionCannotBackTwoTransmittedCloses() {
        fund("0","10");
        var a=preview(OrderSide.SELL,"5"); service.submit(a.orderId(),companyId,1);
        var second=preview(OrderSide.SELL,"5"); service.submit(second.orderId(),companyId,1);
        var opening=preview(OrderSide.SELL,"5"); service.submit(opening.orderId(),companyId,1);
        events.handleFilled(orders.findById(opening.orderId()).orElseThrow(),accounts.findById(accountId).orElseThrow(),
                "PARTIAL-OPENING",b("100"),quote,"test:partial-backing");
        assertThat(balancesQuantity(Asset.XAU)).isEqualByComparingTo("5");
        assertThat(balancesQuantity(Asset.EUR)).isEqualByComparingTo("500");
        assertKind(a.orderId(),"POSITION_CLOSE","5");
        assertKind(second.orderId(),"POSITION_CLOSE","5");
        // FE réelle 975 ; une seule fermeture reste couverte, l'autre exige 26 de RISK.
        // SELL 183 exige 951,60 : admissible à tort si les deux fermetures utilisaient les mêmes 5 oz.
        assertCode(()->preview(OrderSide.SELL,"183"),"INSUFFICIENT_FREE_EQUITY");
        var covered=preview(OrderSide.SELL,"182");
        assertKind(covered.orderId(),"RISK","946.4");
        service.submit(covered.orderId(),companyId,1);
    }

    @Test void unbackedTransmittedCloseRemainsProtectedAfterTtlWhenTradingAnotherMetal() {
        fund("0","10");
        var a=preview(OrderSide.SELL,"10");service.submit(a.orderId(),companyId,1);
        var second=preview(OrderSide.SELL,"10");service.submit(second.orderId(),companyId,1);
        events.handleFilled(orders.findById(second.orderId()).orElseThrow(),accounts.findById(accountId).orElseThrow(),
                "CROSS-METAL-B",b("100"),quote,"test:cross-metal");
        assertThat(jdbc.update("UPDATE trading_reservation SET expires_at=NOW()-INTERVAL '1 day' WHERE order_id=?",a.orderId())).isEqualTo(1);
        jdbc.update("INSERT INTO trading_margin_rate(account_id,asset,rate) VALUES (?,'XAG',0.05)",accountId);
        var silver=new ClientQuote(Asset.XAG,"XAGEUR",b("100"),b("100"),b("100"),b("100"),b("100"),b("100"),
                BigDecimal.ZERO,BigDecimal.ZERO,1,OffsetDateTime.now());
        when(pricing.quoteForDisplay(companyId,Asset.XAG,Asset.EUR)).thenReturn(silver);
        when(pricing.quoteForExecution(companyId,Asset.XAG,Asset.EUR)).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return silver;
        });
        clearInvocations(pricing,provider);
        assertCode(()->service.preview(companyId,1,new OrderPreviewRequest(Asset.XAG,OrderSide.SELL,b("192"),QuantityUnit.OZ,UUID.randomUUID().toString())),
                "INSUFFICIENT_FREE_EQUITY");
        verify(provider,never()).submitSpotOrder(any());
        var covered=service.preview(companyId,1,new OrderPreviewRequest(Asset.XAG,OrderSide.SELL,b("182"),QuantityUnit.OZ,UUID.randomUUID().toString()));
        service.submit(covered.orderId(),companyId,1);
        verify(pricing,atLeastOnce()).quoteForDisplay(companyId,Asset.XAU,Asset.EUR);
        verify(pricing,atLeastOnce()).quoteForExecution(companyId,Asset.XAU,Asset.EUR);
        assertThat(orders.findById(a.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PENDING_UNKNOWN);
        assertKind(a.orderId(),"POSITION_CLOSE","10");
        assertKind(a.orderId(),"RISK","0");
        assertThat(balancesQuantity(Asset.XAU)).isZero();
    }

    @ParameterizedTest
    @CsvSource({
        "10,SELL,4,4,0,0",
        "10,SELL,30,10,0,56",
        "0,SELL,4,0,0,20.8",
        "-10,BUY,4,4,400.8,0",
        "-10,BUY,30,10,3006,56",
        "0,BUY,4,0,400.8,20.8"
    })
    void sixPositionCasesReserveOnlyTheirOwnCashCloseAndRisk(String position,OrderSide side,String qty,String close,String cash,String risk) {
        fund("10000",position);
        var preview=preview(side,qty);
        assertKind(preview.orderId(),"POSITION_CLOSE",close);
        assertKind(preview.orderId(),"CASH",cash);
        assertKind(preview.orderId(),"RISK",risk);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trading_reservation WHERE order_id=? AND reservation_kind='LEGACY'",Integer.class,preview.orderId())).isZero();
    }

    @Test void cashBuyRemainsProtectedDespitePositiveEquity() {
        fund("10","100");
        assertCode(()->preview(OrderSide.BUY,"1"),"INSUFFICIENT_AVAILABLE_BALANCE");
    }

    @Test void riskIsNeverDeductedFromCashAvailable() {
        fund("1000","0");
        preview(OrderSide.SELL,"10");
        assertThat(reservationService.available(accountId,Asset.EUR)).isEqualByComparingTo("1000");
    }

    @Test void accountSpecificMarginIsUsed() {
        fund("10000","0");
        jdbc.update("UPDATE trading_margin_rate SET rate=0.073 WHERE account_id=?",accountId);
        var p=preview(OrderSide.SELL,"10");
        assertKind(p.orderId(),"RISK","75");
        verify(margins,atLeastOnce()).currentRate(accountId,Asset.XAU);
    }

    @Test void missingMarginFailsWithoutFallback() {
        fund("10000","0");
        doThrow(new TradingException(org.springframework.http.HttpStatus.CONFLICT,"MARGIN_RATE_MISSING","test"))
                .when(margins).currentRate(accountId,Asset.XAU);
        assertCode(()->preview(OrderSide.SELL,"1"),"MARGIN_RATE_MISSING");
    }

    @Test void previewIdempotenceKeepsSameRows() {
        fund("10000","0");
        var request=request(OrderSide.BUY,"10");
        var first=service.preview(companyId,1,request);
        var second=service.preview(companyId,1,request);
        assertThat(second.orderId()).isEqualTo(first.orderId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trading_reservation WHERE order_id=?",Integer.class,first.orderId())).isEqualTo(2);
    }

    @Test void twoConcurrentSellsCannotCloseSameLongTwice() throws Exception {
        fund("1000","10");
        var outcomes=race(()->preview(OrderSide.SELL,"10"),()->preview(OrderSide.SELL,"10"));
        assertThat(outcomes).allSatisfy(o->assertThat(o).isInstanceOf(OrderPreviewResponse.class));
        assertThat(jdbc.queryForObject("SELECT SUM(quantity) FROM trading_reservation WHERE account_id=? AND reservation_kind='POSITION_CLOSE' AND status='ACTIVE'",BigDecimal.class,accountId)).isEqualByComparingTo("10");
        assertThat(reservations.riskReserved(accountId,Asset.EUR,-1)).isEqualByComparingTo("52");
    }

    @Test void twoConcurrentOrdersCannotSpendSameFreeEquity() throws Exception {
        fund("80","0");
        var outcomes=race(()->preview(OrderSide.SELL,"10"),()->preview(OrderSide.SELL,"10"));
        assertThat(outcomes.stream().filter(OrderPreviewResponse.class::isInstance).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(TradingException.class::isInstance).count()).isEqualTo(1);
        assertThat(reservations.riskReserved(accountId,Asset.EUR,-1)).isEqualByComparingTo("52");
    }

    @Test void submitRecalculatesAfterCapacityChanges() {
        fund("100","0");
        var p=preview(OrderSide.SELL,"10");
        ledger.post(accountId,Asset.EUR,b("-60"),LedgerEntryType.ADJUSTMENT,null,null,"test:capacity");
        assertCode(()->service.submit(p.orderId(),companyId,1),"INSUFFICIENT_FREE_EQUITY");
        verify(provider,never()).submitSpotOrder(any());
        assertThat(orders.findById(p.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.DRAFT);
        assertKind(p.orderId(),"RISK","52");
    }

    @Test void submitRecalculatesAccountMarginRate() {
        fund("10000","0");
        var p=preview(OrderSide.SELL,"10");
        jdbc.update("UPDATE trading_margin_rate SET rate=0.1 WHERE account_id=?",accountId);
        service.submit(p.orderId(),companyId,1);
        assertKind(p.orderId(),"RISK","102");
    }

    @Test void doubleSubmitCallsProviderOnce() throws Exception {
        fund("10000","0");
        var p=preview(OrderSide.BUY,"1");
        var outcomes=race(()->service.submit(p.orderId(),companyId,1),()->service.submit(p.orderId(),companyId,1));
        assertThat(outcomes).allSatisfy(o->assertThat(o).isInstanceOf(TradingOrder.class));
        verify(provider,times(1)).submitSpotOrder(any());
    }

    @Test void pendingUnknownKeepsAllKindsAfterTtlWhileDraftExpires() {
        fund("10000","-10");
        var pending=preview(OrderSide.BUY,"30");
        service.submit(pending.orderId(),companyId,1);
        var draft=preview(OrderSide.SELL,"1");
        jdbc.update("UPDATE trading_reservation SET expires_at=NOW()-INTERVAL '1 minute' WHERE account_id=?",accountId);
        reservationService.expireDue();
        assertKind(pending.orderId(),"CASH","3006");
        assertKind(pending.orderId(),"POSITION_CLOSE","10");
        assertKind(pending.orderId(),"RISK","56");
        assertThat(reservations.cashReserved(accountId,Asset.EUR,-1)).isEqualByComparingTo("3006");
        assertThat(reservations.closeReserved(accountId,Asset.XAU,OrderSide.BUY,-1)).isEqualByComparingTo("10");
        assertThat(reservations.riskReserved(accountId,Asset.EUR,-1)).isEqualByComparingTo("56");
        assertThat(reservations.hasActiveForOrder(pending.orderId())).isTrue();
        assertThat(reservations.hasActiveForOrder(draft.orderId())).isFalse();
        assertThat(orders.findById(draft.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.EXPIRED);
    }

    @Test void rejectedProviderReleasesEveryReservation() {
        fund("10000","-10");
        doReturn(new OrderAcknowledgement(AcknowledgementState.REJECTED,"CL",null,null,"REJECTED","test")).when(provider).submitSpotOrder(any());
        var p=preview(OrderSide.BUY,"30");
        service.submit(p.orderId(),companyId,1);
        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM trading_reservation WHERE order_id=?",String.class,p.orderId())).containsExactly("RELEASED");
    }

    @Test void doubleFilledPostsOnlyOneTradeAndConsumes() throws Exception {
        fund("10000","0");
        var p=preview(OrderSide.SELL,"10");
        service.submit(p.orderId(),companyId,1);
        var order=orders.findById(p.orderId()).orElseThrow();
        var account=accounts.findById(accountId).orElseThrow();
        var outcomes=race(()->{events.handleFilled(order,account,"EX-B",b("100"),quote,"test:filled");return true;},
                ()->{events.handleFilled(order,account,"EX-B",b("100"),quote,"test:filled");return true;});
        assertThat(outcomes).containsExactly(true,true);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trading_ledger_entry WHERE order_id=?",Integer.class,p.orderId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT quantity FROM trading_balance WHERE account_id=? AND asset='XAU'",BigDecimal.class,accountId)).isEqualByComparingTo("-10");
        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM trading_reservation WHERE order_id=?",String.class,p.orderId())).containsExactly("CONSUMED");
    }

    @ParameterizedTest @CsvSource({"BUY,20,true","BUY,100,true","BUY,120,false","SELL,20,false"})
    void underMarginAllowsOnlyImprovingFullClose(OrderSide side,String qty,boolean accepted) {
        fund("10400","-100");
        if (accepted) {
            var p=preview(side,qty);
            assertKind(p.orderId(),"POSITION_CLOSE",qty);
            assertKind(p.orderId(),"RISK","0");
            service.submit(p.orderId(),companyId,1);
        } else assertThatThrownBy(()->preview(side,qty)).isInstanceOf(TradingException.class);
    }

    @Test void shortCapacityUsesExactFormulaInsteadOfNominalCash() {
        fund("300000","0");
        BigDecimal perOz=b("100").multiply(b("0.05")).add(b("100").subtract(b("100").multiply(BigDecimal.ONE.subtract(b("0.002")))));
        BigDecimal quantity=b("300000").divide(perOz,6,RoundingMode.FLOOR);
        var p=preview(OrderSide.SELL,quantity.toPlainString());
        assertKind(p.orderId(),"CASH","0");
        assertThat(reservations.riskReserved(accountId,Asset.EUR,-1)).isEqualByComparingTo("300000");
        assertThat(quantity.multiply(b("100"))).isGreaterThan(b("5000000"));
        assertCode(()->preview(OrderSide.SELL,"1"),"INSUFFICIENT_FREE_EQUITY");
    }

    @Test void signedProjectionRecordsCurrencyAndMetalFacts() {
        fund("10","0");
        ledger.post(accountId,Asset.EUR,b("-11"),LedgerEntryType.ADJUSTMENT,null,null,"test");
        ledger.post(accountId,Asset.XAU,b("-1"),LedgerEntryType.ADJUSTMENT,null,null,"test");
        assertThat(jdbc.queryForObject("SELECT quantity FROM trading_balance WHERE account_id=? AND asset='EUR'",BigDecimal.class,accountId)).isEqualByComparingTo("-1");
        assertThat(jdbc.queryForObject("SELECT quantity FROM trading_balance WHERE account_id=? AND asset='XAU'",BigDecimal.class,accountId)).isEqualByComparingTo("-1");
    }

    @Test void legacyPendingRemainsConservativeBeyondExpiry() {
        fund("1000","0");
        var p=preview(OrderSide.BUY,"1");
        jdbc.update("UPDATE trading_reservation SET reservation_kind='LEGACY' WHERE order_id=? AND reservation_kind='CASH'",p.orderId());
        jdbc.update("UPDATE trading_order SET status='PENDING_UNKNOWN' WHERE id=?",p.orderId());
        jdbc.update("UPDATE trading_reservation SET expires_at=NOW()-INTERVAL '1 day' WHERE order_id=?",p.orderId());
        reservationService.expireDue();
        assertThat(reservations.cashReserved(accountId,Asset.EUR,-1)).isEqualByComparingTo("100.2");
        assertThat(reservations.riskReserved(accountId,Asset.EUR,-1)).isEqualByComparingTo("105.4");
    }

    @Test void submitRevalidatesDealAndPositionLimits() {
        fund("10000","0");
        var p=preview(OrderSide.BUY,"10");
        jdbc.update("UPDATE trading_account SET deal_limit=500 WHERE id=?",accountId);
        assertCode(()->service.submit(p.orderId(),companyId,1),"DEAL_LIMIT_EXCEEDED");
        jdbc.update("UPDATE trading_account SET deal_limit=NULL,position_limit=500 WHERE id=?",accountId);
        assertCode(()->service.submit(p.orderId(),companyId,1),"POSITION_LIMIT_EXCEEDED");
        verify(provider,never()).submitSpotOrder(any());
    }

    @Test void failedPreviewRollsBackDraftAndAlreadyWrittenReservations() {
        fund("10000","-10");
        var request=request(OrderSide.BUY,"30");
        doAnswer(call->{
            Object id=call.callRealMethod();
            if (call.getArgument(4)==ReservationKind.POSITION_CLOSE) throw new IllegalStateException("injected after close");
            return id;
        }).when(reservations).upsert(eq(accountId),any(),any(),anyLong(),any(),any());
        assertThatThrownBy(()->service.preview(companyId,1,request)).isInstanceOf(IllegalStateException.class);
        assertThat(orders.findByIdempotencyKey(request.idempotencyKey())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trading_reservation WHERE account_id=?",Integer.class,accountId)).isZero();
    }

    @Test void previewSuspendsOuterTransactionAndCommitsIndependentlyOfItsRollback() {
        fund("10000","0");
        var id=new AtomicLong();
        var outer=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        doAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return quote;
        }).when(pricing).quoteForDisplay(companyId,Asset.XAU,Asset.EUR);
        outer.executeWithoutResult(status->{
            long transaction=jdbc.queryForObject("SELECT txid_current()",Long.class);
            id.set(preview(OrderSide.BUY,"1").orderId());
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(jdbc.queryForObject("SELECT txid_current()",Long.class)).isEqualTo(transaction);
            assertIndependentlyCommittedAndUnlocked(id.get(),"DRAFT",2);
            status.setRollbackOnly();
        });
        assertIndependentlyCommittedAndUnlocked(id.get(),"DRAFT",2);
    }

    @Test void submitSuspendsOuterTransactionAndProviderSeesCommittedUnlockedAdmission() {
        fund("10000","-10");
        var p=preview(OrderSide.BUY,"30");
        doAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertIndependentlyCommittedAndUnlocked(p.orderId(),"PENDING",3);
            return new OrderAcknowledgement(AcknowledgementState.IN_PROCESS,"CL",null,null,null,null);
        }).when(provider).submitSpotOrder(any());
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status->{
            long transaction=jdbc.queryForObject("SELECT txid_current()",Long.class);
            service.submit(p.orderId(),companyId,1);
            assertThat(jdbc.queryForObject("SELECT txid_current()",Long.class)).isEqualTo(transaction);
            status.setRollbackOnly();
        });
        assertIndependentlyCommittedAndUnlocked(p.orderId(),"PENDING_UNKNOWN",3);
    }

    @Test void failedSettlementRollsBackLedgerBalancesFilledAndConsumedTogether() {
        fund("10000","-10");
        var p=preview(OrderSide.BUY,"30");
        service.submit(p.orderId(),companyId,1);
        var order=orders.findById(p.orderId()).orElseThrow();
        var account=accounts.findById(accountId).orElseThrow();
        var before=jdbc.queryForList("SELECT asset,quantity FROM trading_balance WHERE account_id=? ORDER BY asset",accountId);
        doAnswer(call->{call.callRealMethod(); throw new IllegalStateException("injected after consumption");})
                .when(reservations).consumeForOrder(p.orderId());
        assertThatThrownBy(()->events.handleFilled(order,account,"EX-ROLLBACK",b("100"),quote,"test"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(orders.findById(p.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PENDING_UNKNOWN);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trading_ledger_entry WHERE order_id=?",Integer.class,p.orderId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trading_as400_sync_outbox WHERE order_id=?",Integer.class,p.orderId())).isZero();
        assertThat(jdbc.queryForList("SELECT asset,quantity FROM trading_balance WHERE account_id=? ORDER BY asset",accountId)).isEqualTo(before);
        assertKind(p.orderId(),"CASH","3006");
        assertKind(p.orderId(),"POSITION_CLOSE","10");
        assertKind(p.orderId(),"RISK","56");
    }

    @Test void expirationWithStaleDraftCandidatesCannotReleaseConcurrentSubmission() throws Exception {
        fund("10000","-10");
        var p=preview(OrderSide.BUY,"30");
        var admissionLocked=new CountDownLatch(1);
        var allowAdmission=new CountDownLatch(1);
        var candidatesRead=new CountDownLatch(1);
        doAnswer(call->{
            admissionLocked.countDown();
            assertThat(allowAdmission.await(10,TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(capacity).reserve(any(),any(),anyMap(),anyMap(),any(),any(),eq(true),any());
        expiryCandidatesRead=candidatesRead;
        var pool=Executors.newFixedThreadPool(2);
        try {
            var submit=pool.submit(()->service.submit(p.orderId(),companyId,1));
            assertThat(admissionLocked.await(10,TimeUnit.SECONDS)).isTrue();
            // Avance l'échéance de la fixture après sa validation, avant le recalcul verrouillé.
            // Aucun sleep : l'expiration doit lire le DRAFT puis attendre le verrou compte du submit.
            jdbc.update("UPDATE trading_reservation SET expires_at=NOW()-INTERVAL '1 minute' WHERE order_id=?",p.orderId());
            var expiration=pool.submit(()->reservationService.expireDue());
            assertThat(candidatesRead.await(10,TimeUnit.SECONDS)).isTrue();
            allowAdmission.countDown();
            assertThat(submit.get(15,TimeUnit.SECONDS).status()).isEqualTo(OrderStatus.PENDING_UNKNOWN);
            expiration.get(15,TimeUnit.SECONDS);
            jdbc.update("UPDATE trading_reservation SET expires_at=NOW()-INTERVAL '1 minute' WHERE order_id=?",p.orderId());
            reservationService.expireDue();
            assertKind(p.orderId(),"CASH","3006");
            assertKind(p.orderId(),"POSITION_CLOSE","10");
            assertKind(p.orderId(),"RISK","56");
            verify(provider,times(1)).submitSpotOrder(any());
        } finally {
            allowAdmission.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void pendingAlsoKeepsEveryKindAfterTtl() {
        fund("10000","-10");
        var p=preview(OrderSide.BUY,"30");
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status->{
            accounts.lockById(accountId).orElseThrow();
            orders.lockById(p.orderId()).orElseThrow();
            assertThat(orders.markPending(p.orderId())).isEqualTo(1);
        });
        jdbc.update("UPDATE trading_reservation SET expires_at=NOW()-INTERVAL '1 day' WHERE order_id=?",p.orderId());
        reservationService.expireDue();
        assertThat(reservations.cashReserved(accountId,Asset.EUR,-1)).isEqualByComparingTo("3006");
        assertThat(reservations.closeReserved(accountId,Asset.XAU,OrderSide.BUY,-1)).isEqualByComparingTo("10");
        assertThat(reservations.riskReserved(accountId,Asset.EUR,-1)).isEqualByComparingTo("56");
    }

    @Test void suspendedAccountBlocksPreviewAndSubmit() {
        fund("10000","0");
        var p=preview(OrderSide.BUY,"1");
        jdbc.update("UPDATE trading_account SET status='SUSPENDED' WHERE id=?",accountId);
        assertCode(()->preview(OrderSide.SELL,"1"),"ACCOUNT_NOT_ACTIVE");
        assertCode(()->service.submit(p.orderId(),companyId,1),"ACCOUNT_NOT_ACTIVE");
        verify(provider,never()).submitSpotOrder(any());
    }

    @Test void concurrentExternalWithdrawalAndBuyCannotOverallocateCash() throws Exception {
        fund("100","0");
        var command=new com.saamp.trading.transfer.TransferCommand(accountId,Asset.EUR,b("60"),
                TransferDirection.OUT,UUID.randomUUID().toString(),null);
        var outcomes=race(()->transfers.ingest(command,"test:external"),()->preview(OrderSide.BUY,"0.8"));
        assertThat(outcomes.get(0)).isInstanceOf(com.saamp.trading.transfer.TradingTransfer.class);
        // L'ingestion externe conserve sa sémantique : elle peut diminuer une capacité réservée au preview.
        assertThat(jdbc.queryForObject("SELECT quantity FROM trading_balance WHERE account_id=? AND asset='EUR'",BigDecimal.class,accountId))
                .isEqualByComparingTo("40");
        if (outcomes.get(1) instanceof OrderPreviewResponse p) {
            assertCode(()->service.submit(p.orderId(),companyId,1),"INSUFFICIENT_AVAILABLE_BALANCE");
        } else assertThat(outcomes.get(1)).isInstanceOf(TradingException.class);
        transfers.ingest(command,"test:replay");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trading_ledger_entry WHERE transfer_ref=?",Integer.class,command.externalRef())).isEqualTo(1);
        verify(provider,never()).submitSpotOrder(any());
    }

    @Test void submitExcludesItsOwnReservationsAtExactCashAndRiskCapacity() {
        fund("100.2","0");
        var p=preview(OrderSide.BUY,"1");
        assertThat(service.submit(p.orderId(),companyId,1).status()).isEqualTo(OrderStatus.PENDING_UNKNOWN);
        assertKind(p.orderId(),"CASH","100.2");
    }

    private void assertIndependentlyCommittedAndUnlocked(long id,String expectedState,int kinds) {
        try (var connection=com.saamp.trading.support.LocalPostgres.connection()) {
            connection.setAutoCommit(false);
            try (var statement=connection.createStatement()) {
                statement.execute("SET LOCAL lock_timeout='1s'");
                try (var account=statement.executeQuery("SELECT id FROM trading_account WHERE id="+accountId+" FOR UPDATE NOWAIT")) {
                    assertThat(account.next()).isTrue();
                }
                try (var order=statement.executeQuery("SELECT status FROM trading_order WHERE id="+id+" FOR UPDATE NOWAIT")) {
                    assertThat(order.next()).isTrue();
                    assertThat(order.getString(1)).isEqualTo(expectedState);
                }
                try (var rows=statement.executeQuery("SELECT id FROM trading_reservation WHERE order_id="+id+" AND status='ACTIVE' FOR UPDATE NOWAIT")) {
                    int count=0;
                    while (rows.next()) count++;
                    assertThat(count).isEqualTo(kinds);
                }
            } finally { connection.rollback(); }
        } catch (java.sql.SQLException failure) { throw new AssertionError("Admission non commitée ou encore verrouillée",failure); }
    }

    @ParameterizedTest
    @CsvSource({"PENDING,CASH","PENDING_UNKNOWN,CASH","PENDING,POSITION_CLOSE","PENDING_UNKNOWN,POSITION_CLOSE"})
    void transmittedLegacyBlocksBothPreviewAndSubmitUntilResolution(OrderStatus state,String kind) {
        fund("10000","-10");
        var waiting=preview(OrderSide.BUY,"1");
        var legacy=preview(OrderSide.BUY,"2");
        jdbc.update("UPDATE trading_reservation SET reservation_kind='LEGACY',expires_at=NOW()-INTERVAL '1 day' WHERE order_id=? AND reservation_kind=?",legacy.orderId(),kind);
        jdbc.update("UPDATE trading_order SET status=? WHERE id=?",state.name(),legacy.orderId());
        assertThat(reservations.hasActiveTransmittedLegacy(accountId)).isTrue();
        assertCode(()->preview(OrderSide.SELL,"1"),"LEGACY_COMMITMENT_UNRESOLVED");
        assertCode(()->service.submit(waiting.orderId(),companyId,1),"LEGACY_COMMITMENT_UNRESOLVED");
        verify(provider,never()).submitSpotOrder(any());
        reservationService.expireDue();
        assertThat(reservations.hasActiveTransmittedLegacy(accountId)).isTrue();
        events.handleRejected(orders.findById(legacy.orderId()).orElseThrow(),"TEST_REJECT","synthetic");
        assertThat(reservations.hasActiveTransmittedLegacy(accountId)).isFalse();
        assertThat(service.submit(waiting.orderId(),companyId,1).status()).isEqualTo(OrderStatus.PENDING_UNKNOWN);
    }

    @Test void legacyDraftExpiresWithoutBlockingNewAdmission() {
        fund("10000","0");
        var draft=preview(OrderSide.BUY,"1");
        jdbc.update("UPDATE trading_reservation SET reservation_kind='LEGACY' WHERE order_id=? AND reservation_kind='CASH'",draft.orderId());
        assertThat(reservations.hasActiveTransmittedLegacy(accountId)).isFalse();
        var next=preview(OrderSide.SELL,"1");
        jdbc.update("UPDATE trading_reservation SET expires_at=NOW()-INTERVAL '1 day' WHERE order_id=?",draft.orderId());
        reservationService.expireDue();
        assertThat(orders.findById(draft.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM trading_reservation WHERE order_id=?",String.class,draft.orderId())).containsExactly("EXPIRED");
        assertThat(orders.findById(next.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.DRAFT);
    }

    @Test void providerExceptionPersistsUncertaintyAndRetainsAllReservations() {
        fund("10000","-10");
        var p=preview(OrderSide.BUY,"30");
        doThrow(new IllegalStateException("synthetic timeout")).when(provider).submitSpotOrder(any());
        assertThat(service.submit(p.orderId(),companyId,1).status()).isEqualTo(OrderStatus.PENDING_UNKNOWN);
        assertThat(jdbc.queryForObject("SELECT stonex_error_code FROM trading_order WHERE id=?",String.class,p.orderId())).isEqualTo("PROVIDER_UNCERTAIN");
        assertKind(p.orderId(),"CASH","3006");
        assertKind(p.orderId(),"POSITION_CLOSE","10");
        assertKind(p.orderId(),"RISK","56");
        service.submit(p.orderId(),companyId,1);
        verify(provider,times(1)).submitSpotOrder(any());
    }

    @Test void cashRoundingRejectsInsufficientPreviewWithoutPartialWrites() {
        fund("3.006000","0");
        var request=request(OrderSide.BUY,"0.03");
        assertCode(()->service.preview(companyId,1,request),"INSUFFICIENT_AVAILABLE_BALANCE");
        assertThat(orders.findByIdempotencyKey(request.idempotencyKey())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trading_reservation WHERE account_id=?",Integer.class,accountId)).isZero();
        verify(provider,never()).submitSpotOrder(any());
    }

    @ParameterizedTest @CsvSource({"BUY","SELL"})
    void minimumQuantityCannotBeTransmitted(OrderSide side) {
        fund("100","0");
        var request=request(side,"0.000001");
        assertCode(()->service.preview(companyId,1,request),"ORDER_AMOUNT_NOT_SETTLEABLE");
        assertThat(orders.findByIdempotencyKey(request.idempotencyKey())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trading_reservation WHERE account_id=?",Integer.class,accountId)).isZero();
        verify(provider,never()).submitSpotOrder(any());
    }

    @Test void draftWithoutReservationExpiresAtFallbackDeadline() {
        fund("100","0");
        var p=preview(OrderSide.SELL,"0.0001");
        assertThat(new BigDecimal("0.0001").multiply(quote.clientSellPrice()).setScale(2,RoundingMode.HALF_UP)).isEqualByComparingTo("0.01");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trading_reservation WHERE order_id=?",Integer.class,p.orderId())).isZero();
        reservationService.expireDue();
        assertThat(orders.findById(p.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.DRAFT);
        jdbc.update("UPDATE trading_order SET created_at=? WHERE id=?",
                java.time.Instant.now().minus(properties.getReservations().getTtl()).minusSeconds(1).atOffset(java.time.ZoneOffset.UTC),p.orderId());
        reservationService.expireDue();
        assertThat(orders.findById(p.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.EXPIRED);
        verify(provider,never()).submitSpotOrder(any());
    }

    @ParameterizedTest @CsvSource({"100,0","80,0.25"})
    void exactCashSettlesAtClientLimitWithRealSpreadRounding(String market,String spread) {
        fund("3.01","0");
        BigDecimal buy=PriceMath.clientPrice(b(market),b(spread),OrderSide.BUY,6);
        BigDecimal sell=PriceMath.clientPrice(b(market),b(spread),OrderSide.SELL,6);
        quote=new ClientQuote(Asset.XAU,"XAUEUR",b(market),b(market),buy,sell,buy,sell,b(spread),b(spread),1,
                java.time.Instant.now().atOffset(java.time.ZoneOffset.UTC));
        when(pricing.quoteForDisplay(companyId,Asset.XAU,Asset.EUR)).thenReturn(quote);
        BigDecimal fill=b(market).multiply(b("1.002"));
        assertThat(PriceMath.clientPrice(fill,b(spread),OrderSide.BUY,6)).isEqualByComparingTo("100.2");
        doReturn(new OrderAcknowledgement(AcknowledgementState.FILLED,"CL","CASH-LIMIT",fill,null,null))
                .when(provider).submitSpotOrder(any());
        var p=preview(OrderSide.BUY,"0.03");
        assertKind(p.orderId(),"CASH","3.01");
        assertThat(service.submit(p.orderId(),companyId,1).status()).isEqualTo(OrderStatus.FILLED);
        assertThat(balancesQuantity(Asset.EUR)).isZero();
        assertThat(balancesQuantity(Asset.XAU)).isEqualByComparingTo("0.03");
        assertThat(jdbc.queryForObject("SELECT gross_amount FROM trading_order WHERE id=?",BigDecimal.class,p.orderId())).isEqualByComparingTo("3.01");
    }

    @Test void submitRejectsCashOneMicroUnitBelowRoundedDebitAndKeepsDraftReservations() {
        fund("3.01","0");
        var p=preview(OrderSide.BUY,"0.03");
        ledger.post(accountId,Asset.EUR,b("-0.000001"),LedgerEntryType.ADJUSTMENT,null,null,"test:cash-boundary");
        assertCode(()->service.submit(p.orderId(),companyId,1),"INSUFFICIENT_AVAILABLE_BALANCE");
        assertThat(orders.findById(p.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.DRAFT);
        assertKind(p.orderId(),"CASH","3.01");
        assertCode(()->preview(OrderSide.BUY,"0.03"),"INSUFFICIENT_AVAILABLE_BALANCE");
        verify(provider,never()).submitSpotOrder(any());
    }

    @Test void severalPendingBuysCannotSpendTheSameRoundingRemainder() {
        fund("9.02","0");
        var first=preview(OrderSide.BUY,"0.03");
        service.submit(first.orderId(),companyId,1);
        var second=preview(OrderSide.BUY,"0.03");
        service.submit(second.orderId(),companyId,1);
        assertCode(()->preview(OrderSide.BUY,"0.03"),"INSUFFICIENT_AVAILABLE_BALANCE");
        verify(provider,times(2)).submitSpotOrder(any());
        for (long id:List.of(first.orderId(),second.orderId()))
            events.handleFilled(orders.findById(id).orElseThrow(),accounts.findById(accountId).orElseThrow(),"CASH-MULTI",b("100.2"),quote,"test:cash-boundary");
        assertThat(balancesQuantity(Asset.EUR)).isEqualByComparingTo("3.00");
    }

    @ParameterizedTest @CsvSource({"BUY","SELL"})
    void indicativeHalfCentCannotHideUnsettleableLowerBound(OrderSide side) {
        fund("100","0");
        assertThat(b("0.00005").multiply(b("100")).setScale(2,RoundingMode.HALF_UP)).isEqualByComparingTo("0.01");
        var request=request(side,"0.00005");
        assertCode(()->service.preview(companyId,1,request),"ORDER_AMOUNT_NOT_SETTLEABLE");
        assertThat(orders.findByIdempotencyKey(request.idempotencyKey())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trading_reservation WHERE account_id=?",Integer.class,accountId)).isZero();
        var neighbour=preview(side,"0.000051");
        assertThat(service.submit(neighbour.orderId(),companyId,1).status()).isEqualTo(OrderStatus.PENDING_UNKNOWN);
        verify(provider,times(1)).submitSpotOrder(any());
    }

    @ParameterizedTest @CsvSource({"BUY","SELL"})
    void historicalDraftIsRevalidatedForZeroSettlementAtSubmit(OrderSide side) {
        fund("100","0");
        // Brouillon synthétique admissible par la version précédente, reproduite avant correction.
        long id=new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status->{
            accounts.lockById(accountId).orElseThrow();
            String key=UUID.randomUUID().toString();
            long draft=orders.insertDraft(accountId,companyId,Asset.XAU,"XAUEUR",side,b("0.00005"),QuantityUnit.OZ,b("0.00005"),
                    b("100"),b("100"),b("100"),BigDecimal.ZERO,1,key,key);
            orders.lockById(draft).orElseThrow();
            reservations.upsert(accountId,Asset.EUR,side==OrderSide.BUY?b("0.010020"):b("0.01"),draft,
                    side==OrderSide.BUY?ReservationKind.CASH:ReservationKind.RISK,
                    java.time.Instant.now().plus(properties.getReservations().getTtl()).atOffset(java.time.ZoneOffset.UTC));
            return draft;
        });
        var before=jdbc.queryForList("SELECT * FROM trading_reservation WHERE order_id=? ORDER BY id",id);
        assertCode(()->service.submit(id,companyId,1),"ORDER_AMOUNT_NOT_SETTLEABLE");
        assertThat(orders.findById(id).orElseThrow().status()).isEqualTo(OrderStatus.DRAFT);
        assertThat(jdbc.queryForList("SELECT * FROM trading_reservation WHERE order_id=? ORDER BY id",id)).isEqualTo(before);
        verify(provider,never()).submitSpotOrder(any());
    }

    @ParameterizedTest @CsvSource({"PENDING","PENDING_UNKNOWN"})
    void submittedOrdersWithoutReservationsNeverExpire(OrderStatus state) {
        fund("100","0");
        var p=preview(OrderSide.SELL,"0.0001");
        service.submit(p.orderId(),companyId,1);
        jdbc.update("UPDATE trading_order SET status=?,created_at=created_at-INTERVAL '1 day' WHERE id=?",state.name(),p.orderId());
        reservationService.expireDue();
        assertThat(orders.findById(p.orderId()).orElseThrow().status()).isEqualTo(state);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trading_reservation WHERE order_id=?",Integer.class,p.orderId())).isZero();
    }

    @Test void expirationWinnerPreventsTransmissionOfDraftWithoutReservations() throws Exception {
        fund("100","0");
        var p=preview(OrderSide.SELL,"0.0001");
        jdbc.update("UPDATE trading_order SET created_at=created_at-INTERVAL '1 day' WHERE id=?",p.orderId());
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1); var quoted=new CountDownLatch(1);
        doAnswer(call->{
            String state=(String)call.callRealMethod(); locked.countDown();
            assertThat(release.await(10,TimeUnit.SECONDS)).isTrue(); return state;
        }).when(jdbc).queryForObject(eq("SELECT status FROM trading_order WHERE id=? FOR UPDATE"),eq(String.class),eq(p.orderId()));
        when(pricing.quoteForExecution(companyId,Asset.XAU,Asset.EUR)).thenAnswer(call->{quoted.countDown();return quote;});
        var pool=Executors.newFixedThreadPool(2);
        try {
            var expiration=pool.submit(()->reservationService.expireDue());
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            var submit=pool.submit(()->{assertThatThrownBy(()->service.submit(p.orderId(),companyId,1))
                    .isInstanceOfSatisfying(TradingException.class,e->assertThat(e.getCode()).isEqualTo("ORDER_NOT_SUBMITTABLE"));});
            assertThat(quoted.await(10,TimeUnit.SECONDS)).isTrue(); release.countDown();
            expiration.get(15,TimeUnit.SECONDS); submit.get(15,TimeUnit.SECONDS);
            assertThat(orders.findById(p.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.EXPIRED);
            verify(provider,never()).submitSpotOrder(any());
        } finally { release.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void expirationRechecksFallbackDeadlineAfterWaitingForAccountLock() throws Exception {
        fund("100","0");
        var p=preview(OrderSide.SELL,"0.0001");
        jdbc.update("UPDATE trading_order SET created_at=created_at-INTERVAL '1 day' WHERE id=?",p.orderId());
        expiryCandidatesRead=new CountDownLatch(1);
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var holder=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status->{
                accounts.lockById(accountId).orElseThrow();held.countDown();
                try { assertThat(release.await(10,TimeUnit.SECONDS)).isTrue(); } catch (InterruptedException e) { throw new AssertionError(e); }
                jdbc.update("UPDATE trading_order SET created_at=? WHERE id=?",java.time.Instant.now().atOffset(java.time.ZoneOffset.UTC),p.orderId());
            }));
            assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();
            var expiration=pool.submit(()->reservationService.expireDue());
            assertThat(expiryCandidatesRead.await(10,TimeUnit.SECONDS)).isTrue();release.countDown();
            holder.get(15,TimeUnit.SECONDS);expiration.get(15,TimeUnit.SECONDS);
            assertThat(orders.findById(p.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.DRAFT);
        } finally {release.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }

    private BigDecimal balancesQuantity(Asset asset) {
        return jdbc.queryForObject("SELECT quantity FROM trading_balance WHERE account_id=? AND asset=?",BigDecimal.class,accountId,asset.name());
    }

    @SuppressWarnings("unchecked")
    private Object scopedExpiryCandidates(org.mockito.invocation.InvocationOnMock call) {
        String sql=call.getArgument(0);
        sql=sql.replace("WHERE o.status=", "WHERE o.account_id="+accountId+" AND o.status=");
        var actual=new JdbcTemplate(jdbc.getDataSource());
        var mapper=(org.springframework.jdbc.core.RowMapper<long[]>)call.getArgument(1);
        Object[] raw=call.getRawArguments();
        var candidates=raw.length==2?actual.query(sql,mapper):actual.query(sql,mapper,(Object[])raw[2]);
        if (expiryCandidatesRead!=null) expiryCandidatesRead.countDown();
        return candidates;
    }

    private void fund(String cash,String metal) {
        if (b(cash).signum()!=0) ledger.post(accountId,Asset.EUR,b(cash),LedgerEntryType.ADJUSTMENT,null,null,"test:fund");
        if (b(metal).signum()!=0) ledger.post(accountId,Asset.XAU,b(metal),LedgerEntryType.ADJUSTMENT,null,null,"test:fund");
    }
    private OrderPreviewRequest request(OrderSide side,String qty) { return new OrderPreviewRequest(Asset.XAU,side,b(qty),QuantityUnit.OZ,UUID.randomUUID().toString()); }
    private OrderPreviewResponse preview(OrderSide side,String qty) { return service.preview(companyId,1,request(side,qty)); }
    private void assertKind(long id,String kind,String expected) {
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(quantity),0) FROM trading_reservation WHERE order_id=? AND reservation_kind=? AND status='ACTIVE'",BigDecimal.class,id,kind)).isEqualByComparingTo(expected);
    }
    private static BigDecimal b(String value) { return new BigDecimal(value); }
    private static void assertCode(Runnable work,String code) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(TradingException.class,e->assertThat(e.getCode()).isEqualTo(code));
    }
    private static List<Object> race(Callable<?> first,Callable<?> second) throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        try {
            var ready=new CountDownLatch(2); var start=new CountDownLatch(1);
            var futures=new ArrayList<Future<Object>>();
            for (var task:List.of(first,second)) futures.add(pool.submit(()->{
                ready.countDown(); if (!start.await(10,TimeUnit.SECONDS)) throw new AssertionError("barrier");
                try { return task.call(); } catch (Exception failure) { return failure; }
            }));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); start.countDown();
            return List.of(futures.get(0).get(20,TimeUnit.SECONDS),futures.get(1).get(20,TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();
        }
    }
}
