package com.saamp.trading.integration;

import com.saamp.trading.account.*;
import com.saamp.trading.common.TradingException;
import com.saamp.trading.domain.*;
import com.saamp.trading.ledger.LedgerService;
import com.saamp.trading.order.*;
import com.saamp.trading.pricing.*;
import com.saamp.trading.provider.*;
import com.saamp.trading.risk.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@org.springframework.context.annotation.Import(com.saamp.trading.support.LocalPostgres.Context.class)
@SpringBootTest(properties={"trading.provider.mode=SIMULATED","trading.as400.enabled=false","trading.as400.jdbc-url=",
        "trading.effective-balance.overlay-cutover-at=2020-01-01T00:00:00Z",
        "trading.reconciliation.enabled=false","trading.effective-balance.mode=ENFORCED","trading.effective-balance.max-snapshot-age=2m",
        "trading.demo.enabled=true"})
class EffectiveBalanceIntegrationTest {
    @MockitoBean(name="org.springframework.context.annotation.internalScheduledAnnotationProcessor")
    org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor scheduling;
    @MockitoBean OfficialTradingBalanceReader official;
    @MockitoBean PricingService pricing;
    @MockitoBean TradingProvider provider;
    @MockitoBean PendingUnknownResolver resolver;
    @Autowired JdbcTemplate jdbc;
    @Autowired LedgerService ledger;
    @Autowired AccountRepository accounts;
    @Autowired OrderRepository orders;
    @Autowired OrderExecutionService execution;
    @Autowired ExecutionEventHandler fills;
    @Autowired EffectiveBalanceService effective;
    @Autowired PendingTradingAdjustmentRepository pending;
    @Autowired RiskService risk;
    @Autowired PositionService positions;
    @Autowired com.saamp.trading.transfer.TransferService transfers;
    static AtomicLong ids=new AtomicLong(System.currentTimeMillis());
    long accountId,companyId;
    BigDecimal officialCash=new BigDecimal("100");
    Set<Long> posted=new HashSet<>();
    boolean unavailable;
    ClientQuote quote;
    static BigDecimal b(String s) { return new BigDecimal(s); }
    @BeforeEach void setup() {
        companyId=ids.incrementAndGet();officialCash=b("100");posted.clear();unavailable=false;
        accountId=jdbc.queryForObject("INSERT INTO trading_account(company_id,base_currency,status,as400_ste,as400_nucli_trading) VALUES (?,'EUR','ACTIVE','B',20662) RETURNING id",Long.class,companyId);
        jdbc.update("INSERT INTO trading_margin_rate(account_id,asset,rate) VALUES (?,'XAU',0.05)",accountId);
        quote=new ClientQuote(Asset.XAU,"XAUEUR",b("10"),b("10"),b("10"),b("10"),b("10"),b("10"),BigDecimal.ZERO,BigDecimal.ZERO,1,OffsetDateTime.now());
        when(pricing.quoteForDisplay(companyId,Asset.XAU,Asset.EUR)).thenAnswer(call->freshQuote());
        when(pricing.quoteForExecution(companyId,Asset.XAU,Asset.EUR)).thenAnswer(call->freshQuote());
        when(official.read(any(),anyList())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if(unavailable) throw new IllegalStateException("synthetic timeout");
            var amounts=new EnumMap<Asset,BigDecimal>(Asset.class);
            for(var a:List.of(Asset.EUR,Asset.XAU,Asset.XAG,Asset.XPT,Asset.XPD))amounts.put(a,BigDecimal.ZERO);
            amounts.put(Asset.EUR,officialCash);
            var states=new HashMap<Long,Boolean>();
            for(var fact:call.<List<PendingTradingAdjustmentRepository.Adjustment>>getArgument(1)) states.put(fact.orderId(),posted.contains(fact.orderId()));
            return new OfficialTradingBalanceReader.Reading(amounts,states);
        });
        when(provider.supportsSpotOrderSubmission()).thenReturn(true);
        when(provider.submitSpotOrder(any())).thenAnswer(call->new OrderAcknowledgement(AcknowledgementState.FILLED,call.<SpotOrderRequest>getArgument(0).clientOrderId(),"SIM-EFFECTIVE-"+ids.incrementAndGet(),b("10"),null,null));
    }
    ClientQuote freshQuote() {
        return new ClientQuote(Asset.XAU,"XAUEUR",b("10"),b("10"),b("10"),b("10"),b("10"),b("10"),BigDecimal.ZERO,BigDecimal.ZERO,1,OffsetDateTime.now());
    }
    TradingAccount account() { return accounts.findById(accountId).orElseThrow(); }
    OrderPreviewResponse preview(OrderSide side,String quantity) {
        return execution.preview(companyId,1,new OrderPreviewRequest(Asset.XAU,side,b(quantity),QuantityUnit.OZ,UUID.randomUUID().toString()));
    }
    BigDecimal projected(Asset a) {
        return jdbc.queryForObject("SELECT quantity FROM trading_balance WHERE account_id=? AND asset=?",BigDecimal.class,accountId,a.name());
    }
    BigDecimal effectiveCash() { return effective.findAll(accountId).stream().filter(v->v.asset()==Asset.EUR).findFirst().orElseThrow().quantity(); }
    @Test void officialFundsAdmitAndFilledPersistsNegativeLocalProjectionExactlyOnce() {
        var p=preview(OrderSide.BUY,"1");
        var order=execution.submit(p.orderId(),companyId,1);
        assertThat(order.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(projected(Asset.EUR)).isEqualByComparingTo("-10");
        assertThat(projected(Asset.XAU)).isEqualByComparingTo("1");
        assertThat(effectiveCash()).isEqualByComparingTo("90");
        fills.handleFilled(order,account(),order.stonexExid(),b("10"),quote,"replay");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trading_ledger_entry WHERE order_id=?",Integer.class,order.id())).isEqualTo(2);
        assertThat(pending.read(accountId)).hasSize(1);
        assertThat(projected(Asset.EUR)).isEqualByComparingTo("-10");
    }
    @Test void positiveProjectionCannotFundAnEmptyOfficialAccount() {
        ledger.post(accountId,Asset.EUR,b("100"),LedgerEntryType.ADJUSTMENT,null,null,"test");
        officialCash=BigDecimal.ZERO;
        assertThatThrownBy(()->preview(OrderSide.BUY,"1")).isInstanceOf(TradingException.class);
        verify(provider,never()).submitSpotOrder(any());
    }
    @Test void unavailableAs400PreventsNewTrading() {
        unavailable=true;
        assertThatThrownBy(()->preview(OrderSide.BUY,"1")).isInstanceOf(TradingException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trading_order WHERE account_id=?",Integer.class,accountId)).isZero();
        verify(provider,never()).submitSpotOrder(any());
    }
    @Test void demoFilledNeverCreatesAnAs400OutboxMovementOrEffectiveBalanceOverlay() {
        unavailable=true;
        accountId=jdbc.queryForObject("INSERT INTO trading_account(company_id,base_currency,status,account_mode) VALUES (?,'EUR','ACTIVE','DEMO') RETURNING id",Long.class,ids.incrementAndGet());
        companyId=accounts.findById(accountId).orElseThrow().companyId();
        jdbc.update("INSERT INTO trading_margin_rate(account_id,asset,rate) VALUES (?,'XAU',0.05)",accountId);
        when(pricing.quoteForDisplay(companyId,Asset.XAU,Asset.EUR)).thenAnswer(call->freshQuote());
        when(pricing.quoteForExecution(companyId,Asset.XAU,Asset.EUR)).thenAnswer(call->freshQuote());
        jdbc.update("INSERT INTO trading_market_price(pair,bid,ask,mid,price_as_of,source) VALUES ('XAUEUR',10,10,10,NOW(),'FIXTURE') ON CONFLICT(pair) DO UPDATE SET bid=10,ask=10,mid=10,price_as_of=NOW()");
        jdbc.update("INSERT INTO trading_demo_balance(account_id,asset,quantity) VALUES (?,'EUR',100)",accountId);
        var request=new OrderPreviewRequest(Asset.XAU,OrderSide.BUY,BigDecimal.ONE,QuantityUnit.OZ,"demo-"+UUID.randomUUID());

        var preview=execution.preview(companyId,1,request,TradingMode.DEMO);
        var filled=execution.submit(preview.orderId(),companyId,1,TradingMode.DEMO);

        assertThat(filled.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(jdbc.queryForObject("SELECT trading_mode FROM trading_order WHERE id=?",String.class,filled.id())).isEqualTo("DEMO");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trading_demo_ledger_entry WHERE order_id=?",Integer.class,filled.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trading_ledger_entry WHERE order_id=?",Integer.class,filled.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trading_as400_sync_outbox WHERE order_id=?",Integer.class,filled.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trading_as400_movement m JOIN trading_as400_sync_outbox e ON e.id=m.event_id WHERE e.order_id=?",Integer.class,filled.id())).isZero();
        assertThat(pending.read(accountId)).isEmpty();
        fills.handleFilled(filled,account(),filled.stonexExid(),b("10"),quote,"replay");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trading_demo_ledger_entry WHERE order_id=?",Integer.class,filled.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trading_as400_sync_outbox WHERE order_id=?",Integer.class,filled.id())).isZero();
        verifyNoInteractions(official);
    }
    @Test void as400FailureAfterIrreversibleProviderFillDoesNotPreventLedger() {
        var p=preview(OrderSide.BUY,"1");
        doAnswer(call->{unavailable=true;return new OrderAcknowledgement(AcknowledgementState.FILLED,"CL","SIM-EFFECTIVE-FILLED",b("10"),null,null);}).when(provider).submitSpotOrder(any());
        assertThat(execution.submit(p.orderId(),companyId,1).status()).isEqualTo(OrderStatus.FILLED);
        assertThat(projected(Asset.EUR)).isEqualByComparingTo("-10");
    }
    @Test void sellShortThenBuyClosingKeepsRealSignedDeltas() {
        var sell=preview(OrderSide.SELL,"1");execution.submit(sell.orderId(),companyId,1);
        assertThat(projected(Asset.XAU)).isEqualByComparingTo("-1");
        var buy=preview(OrderSide.BUY,"1");execution.submit(buy.orderId(),companyId,1);
        assertThat(projected(Asset.XAU)).isZero();assertThat(projected(Asset.EUR)).isZero();assertThat(effectiveCash()).isEqualByComparingTo("100");
    }
    @ParameterizedTest @ValueSource(strings={"PENDING","RETRY","BLOCKED"})
    void outboxStatusCannotDiscardFilledAdjustment(String status) {
        var p=preview(OrderSide.BUY,"1");execution.submit(p.orderId(),companyId,1);
        jdbc.update("UPDATE trading_as400_sync_outbox SET status=? WHERE order_id=? AND target='SICOUVI'",status,p.orderId());
        assertThat(effectiveCash()).isEqualByComparingTo("90");
        assertThat(pending.read(accountId)).hasSize(1);
    }
    @Test void partialGroupWithOnlyIntercoDoesNotRemoveClientAdjustment() {
        var p=preview(OrderSide.BUY,"1");execution.submit(p.orderId(),companyId,1);
        jdbc.update("""
            INSERT INTO trading_as400_movement(event_id,leg_index,leg_role,siste,nucli,sicoui,siacfv,simet,sipds,sicot,sitxch,siref3,sicnd,siref2,execution_date,execution_time)
            SELECT id,1,'INTERCO_LFMP','B',15001,2,'A','O',0.01,1,1,'PARTIAL','TEST','TEST',20260911,120000
            FROM trading_as400_sync_outbox WHERE order_id=? AND target='SICOUVI'
            """,p.orderId());
        jdbc.update("UPDATE trading_as400_sync_outbox SET status='BLOCKED' WHERE order_id=? AND target='SICOUVI'",p.orderId());
        assertThat(pending.read(accountId).getFirst().movementId()).isNull();
        assertThat(effectiveCash()).isEqualByComparingTo("90");
    }
    @Test void filledWithoutSicouviEventStillContributes() {
        var p=preview(OrderSide.BUY,"1");execution.submit(p.orderId(),companyId,1);
        jdbc.update("DELETE FROM trading_as400_sync_outbox WHERE order_id=? AND target='SICOUVI'",p.orderId());
        assertThat(pending.read(accountId).getFirst().eventId()).isNull();
        assertThat(effectiveCash()).isEqualByComparingTo("90");
        assertThat(effective.capture(account()).pendingAdjustments().get(Asset.EUR)).isEqualByComparingTo("-10");
    }
    @ParameterizedTest @ValueSource(strings={"2019-12-31T23:59:59.999999Z","2020-01-01T00:00:00Z","2020-01-01T00:00:00.000001Z"})
    void executedAtCutoverSelectionSurvivesRepositoryAndServiceRestart(String executedAt) {
        var p=preview(OrderSide.BUY,"1");execution.submit(p.orderId(),companyId,1);
        var executionTime=OffsetDateTime.parse(executedAt);
        jdbc.update("UPDATE trading_order SET executed_at=? WHERE id=?",executionTime,p.orderId());
        boolean included=!executionTime.toInstant().isBefore(java.time.Instant.parse("2020-01-01T00:00:00Z"));
        var selected=pending.read(accountId);
        assertThat(selected).hasSize(included?1:0);
        if(included) assertThat(selected.getFirst().orderId()).isEqualTo(p.orderId());
        assertThat(effectiveCash()).isEqualByComparingTo(included?"90":"100");

        var restartedConfig=new EffectiveBalanceProperties();
        restartedConfig.setMode(EffectiveBalanceProperties.Mode.ENFORCED);
        restartedConfig.setOverlayCutoverAt(java.time.Instant.parse("2020-01-01T00:00:00Z"));
        restartedConfig.setMaxSnapshotAge(java.time.Duration.ofMinutes(2));
        var restartedRepository=new PendingTradingAdjustmentRepository(new JdbcTemplate(jdbc.getDataSource()),restartedConfig);
        var restartedService=new EffectiveBalanceService(mock(BalanceRepository.class),accounts,restartedRepository,official,restartedConfig);
        assertThat(restartedRepository.read(accountId)).isEqualTo(selected);
        var s=restartedService.capture(account());
        assertThat(s.pendingAdjustments().getOrDefault(Asset.EUR,BigDecimal.ZERO)).isEqualByComparingTo(included?"-10":"0");
        assertThat(s.clientPosted().containsKey(p.orderId())).isEqualTo(included);
        assertThat(s.facts()).isEqualTo(selected);
    }
    @Test void clientImputationRemovesOverlayIndependentlyOfGroupStatus() {
        var p=preview(OrderSide.BUY,"1");execution.submit(p.orderId(),companyId,1);
        posted.add(p.orderId());officialCash=b("90");
        assertThat(effectiveCash()).isEqualByComparingTo("90");
        assertThat(effective.capture(account()).pendingAdjustments()).isEmpty();
        assertThat(projected(Asset.EUR)).isEqualByComparingTo("-10");
    }
    @Test void riskUsesSameEffectiveFundsAsAdmission() {
        var p=preview(OrderSide.BUY,"1");execution.submit(p.orderId(),companyId,1);
        var result=risk.computeAndStore(account());
        assertThat(result.totalFunds()).isEqualByComparingTo(effectiveCash());
        assertThat(result.positionValuation()).isEqualByComparingTo("10");
        assertThat(positions.read(account())).hasSize(1);
    }
    @Test void externallyCompletedTransferOutAndStatementRemainLocalFacts() {
        var command=new com.saamp.trading.transfer.TransferCommand(accountId,Asset.EUR,b("10"),TransferDirection.OUT,"effective-"+companyId,null);
        transfers.ingest(command,"test");transfers.ingest(command,"replay");
        assertThat(projected(Asset.EUR)).isEqualByComparingTo("-10");
        assertThat(effectiveCash()).isEqualByComparingTo("100");
        var statement=new com.saamp.trading.statement.StatementService(new com.saamp.trading.ledger.LedgerRepository(jdbc)).build(account(),null,100);
        assertThat(statement.lines()).hasSize(1);
        assertThat(statement.lines().getFirst().balanceAfter()).isEqualByComparingTo("-10");
    }
    @Test void twoConcurrentBuysCannotReserveTheSameOfficialCash() throws Exception {
        officialCash=b("15");
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<Boolean> attempt=()->{start.await();try{preview(OrderSide.BUY,"1");return true;}catch(TradingException denied){return false;}};
            var a=pool.submit(attempt);var c=pool.submit(attempt);start.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),c.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        } finally {pool.shutdownNow();}
    }
}
