package com.saamp.trading.integration;

import com.saamp.trading.account.*;
import com.saamp.trading.configsync.*;
import com.saamp.trading.domain.*;
import com.saamp.trading.ledger.LedgerService;
import com.saamp.trading.order.*;
import com.saamp.trading.pricing.*;
import com.saamp.trading.provider.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.springframework.context.annotation.Import(com.saamp.trading.support.LocalPostgres.Context.class)
@SpringBootTest(properties={"trading.provider.mode=SIMULATED","trading.as400.enabled=false","trading.as400.jdbc-url=","trading.reconciliation.enabled=false","trading.demo.enabled=true"})
class AbsoluteSpreadTradingIntegrationTest {
    @MockitoBean(name="org.springframework.context.annotation.internalScheduledAnnotationProcessor")
    org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor scheduling;
    @MockitoBean TradingProvider provider;
    @MockitoBean MarketDataRefreshService refresh;
    @MockitoBean PendingUnknownResolver scheduledResolver;
    @Autowired JdbcTemplate jdbc;
    @Autowired TradingConfigurationSyncService config;
    @Autowired LedgerService ledger;
    @Autowired OrderExecutionService execution;
    @Autowired ExecutionEventHandler events;
    @Autowired OrderRepository orders;
    @Autowired AccountRepository accounts;
    @Autowired PricingRepository market;
    @MockitoSpyBean PricingService pricing;
    private static final AtomicLong IDS=new AtomicLong(System.currentTimeMillis()+100000);
    long company,account;
    static BigDecimal b(String n) {return new BigDecimal(n);}

    @BeforeEach void setup() {
        company=IDS.incrementAndGet();
        account=jdbc.queryForObject("INSERT INTO trading_account(company_id,base_currency,as400_ste,as400_nucli_trading) VALUES(?,'EUR','B',10002) RETURNING id",Long.class,company);
        config.applySpread(new SpreadConfigCommand(company,Asset.XAG,b("0.10"),b("0.10"),1,OffsetDateTime.now().minusMinutes(1),SpreadType.ABSOLUTE,"OZ"));
        for(String currency:List.of("EUR","USD")) market.upsertMarketPrice(new MarketPrice("XAG"+currency,b("70"),b("70.10"),b("70.05"),OffsetDateTime.now(),"PMXCONNECT_TEST",OffsetDateTime.now()));
        ledger.post(account,Asset.EUR,b("10000"),LedgerEntryType.ADJUSTMENT,null,null,"test:fund");
        ledger.post(account,Asset.XAG,b("2"),LedgerEntryType.ADJUSTMENT,null,null,"test:fund");
        jdbc.update("UPDATE trading_execution_gate SET open=true WHERE id=1");
        when(provider.supportsSpotOrderSubmission()).thenReturn(true);
        when(provider.submitSpotOrder(any())).thenAnswer(c->new OrderAcknowledgement(AcknowledgementState.IN_PROCESS,c.getArgument(0,SpotOrderRequest.class).clientOrderId(),null,null,null,null));
    }
    OrderPreviewRequest request(OrderSide side) {return new OrderPreviewRequest(Asset.XAG,side,BigDecimal.ONE,QuantityUnit.OZ,UUID.randomUUID().toString());}

    @ParameterizedTest @EnumSource(OrderSide.class)
    void previewAndSubmitKeepTheirSpreadAfterConfigChanges(OrderSide side) {
        var request=request(side);var preview=execution.preview(company,99,request);
        assertThat(preview.indicativeClientPrice()).isEqualByComparingTo(side==OrderSide.BUY?"70.20":"69.90");
        assertThat(execution.preview(company,99,request).orderId()).isEqualTo(preview.orderId());
        config.applySpread(new SpreadConfigCommand(company,Asset.XAG,b("1.00"),b("1.00"),2,OffsetDateTime.now(),SpreadType.ABSOLUTE,"OZ"));
        var submitted=execution.submit(preview.orderId(),company,99);
        assertThat(submitted.status()).isEqualTo(OrderStatus.PENDING_UNKNOWN);
        assertThat(submitted.spreadApplied()).isEqualByComparingTo("0.10");
        assertThat(submitted.spreadType()).isEqualTo(SpreadType.ABSOLUTE);
        assertThat(submitted.spreadQuoteCurrency()).isEqualTo("EUR");
        execution.submit(submitted.id(),company,99);
        verify(provider,times(1)).submitSpotOrder(any());
        // Un changement de precision/config apres transmission ne change pas le settlement.
        var changedQuote=pricing.quoteForDisplay(company,Asset.XAG,Asset.EUR);
        events.handleFilled(submitted,accounts.findById(account).orElseThrow(),"EX-SNAPSHOT-"+submitted.id(),b("70"),changedQuote,"test:filled");
        var filled=orders.findById(submitted.id()).orElseThrow();
        assertThat(filled.clientPrice()).isEqualTo(b(side==OrderSide.BUY?"70.100000":"69.900000"));
        assertThat(filled.spreadConfigVersion()).isEqualTo(1);
        events.handleFilled(filled,accounts.findById(account).orElseThrow(),filled.stonexExid(),b("70"),null,"test:replay");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trading_ledger_entry WHERE order_id=?",Integer.class,filled.id())).isEqualTo(2);
    }
    @ParameterizedTest @EnumSource(value=OrderStatus.class,names={"PENDING","PENDING_UNKNOWN"})
    void pendingRecoveryUsesOnlyPersistedSnapshotWithoutFetchingMarketOrSpread(OrderStatus recoveredState) {
        var p=execution.preview(company,99,request(OrderSide.SELL));execution.submit(p.orderId(),company,99);
        jdbc.update("UPDATE trading_order SET status=? WHERE id=?",recoveredState.name(),p.orderId());
        var order=orders.findById(p.orderId()).orElseThrow();
        jdbc.update("DELETE FROM trading_spread WHERE company_id=?",company);
        var candidates=mock(OrderRepository.class);when(candidates.findPendingUnknownDue(50)).thenReturn(List.of(order));
        if(recoveredState==OrderStatus.PENDING) {
            doAnswer(c->{orders.markPendingUnknown(order.id(),"SUBMISSION_STATE_UNKNOWN","test:restart");return null;}).when(candidates).markPendingUnknown(eq(order.id()),anyString(),anyString());
            when(candidates.findById(order.id())).thenAnswer(c->orders.findById(order.id()));
        }
        when(provider.queryRequestStatus(order.clOrdId())).thenReturn(Optional.of(new ExecutionReport(ExecutionState.PROCESSED,order.clOrdId(),"EX-RECOVERY",b("70.25"),null,null)));
        clearInvocations(pricing,refresh,provider);
        new PendingUnknownResolver(candidates,accounts,provider,pricing,events).resolveDue();
        assertThat(orders.findById(order.id()).orElseThrow().clientPrice()).isEqualByComparingTo("70.15");
        verifyNoInteractions(pricing,refresh);verify(provider,never()).submitSpotOrder(any());
    }
    @ParameterizedTest @EnumSource(value=Asset.class,names={"EUR","USD"})
    void demoUsesAbsoluteQuoteAndLocalExecutionOnly(Asset currency) {
        long demoCompany=IDS.incrementAndGet();
        long demo=jdbc.queryForObject("INSERT INTO trading_account(company_id,base_currency,account_mode) VALUES(?,?,'DEMO') RETURNING id",Long.class,demoCompany,currency.name());
        jdbc.update("INSERT INTO trading_demo_balance(account_id,asset,quantity) VALUES(?,?,10000)",demo,currency.name());
        config.applySpread(new SpreadConfigCommand(demoCompany,Asset.XAG,b("0.10"),b("0.10"),1,OffsetDateTime.now(),SpreadType.ABSOLUTE,"OZ"));
        var p=execution.preview(demoCompany,99,request(OrderSide.BUY),TradingMode.DEMO);
        assertThat(p.pair()).isEqualTo("XAG"+currency.name());
        var filled=execution.submit(p.orderId(),demoCompany,99,TradingMode.DEMO);
        assertThat(filled.status()).isEqualTo(OrderStatus.FILLED);assertThat(filled.stonexExid()).startsWith("SIM-");
        assertThat(filled.clientPrice()).isEqualByComparingTo("70.20");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trading_demo_ledger_entry WHERE order_id=?",Integer.class,filled.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trading_ledger_entry WHERE order_id=?",Integer.class,filled.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trading_as400_sync_outbox WHERE order_id=?",Integer.class,filled.id())).isZero();
        verify(provider,never()).submitSpotOrder(any());
    }
}
