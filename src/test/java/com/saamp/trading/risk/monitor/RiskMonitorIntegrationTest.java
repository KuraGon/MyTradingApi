package com.saamp.trading.risk.monitor;

import com.saamp.trading.account.*;
import com.saamp.trading.config.TradingProperties;
import com.saamp.trading.domain.*;
import com.saamp.trading.pricing.*;
import com.saamp.trading.provider.*;
import com.saamp.trading.risk.*;
import com.saamp.trading.ledger.*;
import com.saamp.trading.as400.As400SyncOutboxRepository;
import com.saamp.trading.support.LocalPostgres;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.*;
import org.springframework.mail.javamail.JavaMailSender;
import jakarta.mail.internet.MimeMessage;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Vrais composants Spring/JDBC, PostgreSQL local et SMTP doubl? ; chaque test ne touche que son schÃƒÆ’Ã‚Â©ma synthÃƒÆ’Ã‚Â©tique vÃƒÆ’Ã‚Â©rifiÃƒÆ’Ã‚Â©. */
class RiskMonitorIntegrationTest {
    String schema;
    DataSource ds;
    JdbcTemplate jdbc;
    AnnotationConfigApplicationContext context;
    RiskMonitorRepository repository;
    RiskMonitorService service;
    AccountRepository accounts;
    BalanceRepository balances;
    PricingRepository prices;
    TradingProvider provider;
    RiskMonitorProperties config=RiskMonitorPolicyTest.config();
    TransactionTemplate tx;
    long id;
    List<Map<String,Object>> historical;
    static BigDecimal b(String s) { return new BigDecimal(s); }

    @BeforeEach void setup() throws Exception {
        schema=LocalPostgres.createSchema();
        ds=LocalPostgres.dataSource(schema);
        jdbc=spy(new JdbcTemplate(ds));
        migrate(12);
        historical=jdbc.queryForList("SELECT * FROM databasechangelog ORDER BY orderexecuted");
        migrate(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM databasechangelog",Integer.class)).isEqualTo(13);
        migrate(2);
        context=new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class,()->ds);
        context.registerBean(JdbcTemplate.class,()->jdbc);
        context.registerBean(DataSourceTransactionManager.class,()->new DataSourceTransactionManager(ds));
        context.registerBean(AccountRepository.class,()->new AccountRepository(jdbc));
        context.registerBean(BalanceRepository.class,()->new BalanceRepository(jdbc));
        context.registerBean(EffectiveBalanceService.class,()->new EffectiveBalanceService(context.getBean(BalanceRepository.class),context.getBean(AccountRepository.class),null,null,new EffectiveBalanceProperties()));
        context.registerBean(PricingRepository.class,()->new PricingRepository(jdbc));
        provider=mock(TradingProvider.class);
        when(provider.fetchSpotRates(anySet())).thenReturn(List.of());
        context.registerBean(TradingProvider.class,()->provider);
        context.registerBean(MarketPriceService.class,()->new MarketPriceService(jdbcPrices(),new TradingProperties()));
        context.registerBean(MarketDataRefreshService.class,()->new MarketDataRefreshService(provider,context.getBean(MarketPriceService.class)));
        context.registerBean(PricingService.class,()->new PricingService(jdbcPrices(),context.getBean(MarketPriceService.class),context.getBean(MarketDataRefreshService.class)));
        context.registerBean(MarginRateRepository.class,()->new MarginRateRepository(jdbc));
        context.registerBean(PositionService.class,()->new PositionService(context.getBean(EffectiveBalanceService.class),context.getBean(PricingService.class),context.getBean(MarginRateRepository.class)));
        context.registerBean(RiskService.class,()->new RiskService(context.getBean(EffectiveBalanceService.class),context.getBean(PositionService.class),new RiskSnapshotRepository(jdbc)));
        context.registerBean(RiskMonitorRepository.class,()->new RiskMonitorConfiguration().riskMonitorRepository(jdbc,context.getBean(DataSourceTransactionManager.class),context.getBean(AccountRepository.class),new ObjectMapper().findAndRegisterModules()));
        context.registerBean(RiskMonitorService.class,()->new RiskMonitorConfiguration().riskMonitorService(context.getBean(RiskMonitorRepository.class),context.getBean(AccountRepository.class),context.getBean(EffectiveBalanceService.class),context.getBean(PricingService.class),context.getBean(MarginRateRepository.class),context.getBean(RiskService.class),config));
        context.refresh();
        repository=context.getBean(RiskMonitorRepository.class);service=context.getBean(RiskMonitorService.class);
        accounts=context.getBean(AccountRepository.class);balances=context.getBean(BalanceRepository.class);prices=jdbcPrices();
        tx=new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        id=jdbc.queryForObject("INSERT INTO trading_account(company_id,base_currency,status) VALUES (1,'EUR','ACTIVE') RETURNING id",Long.class);
        for(String asset:List.of("XAU","XAG")) {
            jdbc.update("INSERT INTO trading_spread(company_id,asset,spread_buy,spread_sell,config_version) VALUES (1,?,0.01,0.01,1)",asset);
            market(asset,Instant.now());
        }
    }
    PricingRepository jdbcPrices() { return context.getBean(PricingRepository.class); }
    void migrate(int count) throws Exception {
        try(var c=ds.getConnection()) {
            var db=liquibase.database.DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new liquibase.database.jvm.JdbcConnection(c));
            db.setDefaultSchemaName(schema);db.setLiquibaseSchemaName(schema);
            var runner=new liquibase.Liquibase("db/changelog/db.changelog-master.yaml",new liquibase.resource.ClassLoaderResourceAccessor(),db);
            runner.update(count,new liquibase.Contexts(),new liquibase.LabelExpression());
        }
    }
    @AfterEach void cleanup() throws Exception {
        if(context!=null) context.close();
        if(ds!=null) LocalPostgres.dropSchema(schema);
    }
    void market(String asset,Instant at) {
        prices.upsertMarketPrice(new MarketPrice(asset+"EUR",b("100"),b("100"),null,at.atOffset(ZoneOffset.UTC),"TEST",Instant.now().atOffset(ZoneOffset.UTC)));
    }
    void fund(Asset asset,String amount) {
        var ledger=new LedgerService(balances,new LedgerRepository(jdbc),new As400SyncOutboxRepository(jdbc));
        tx.executeWithoutResult(s->ledger.post(id,asset,b(amount),LedgerEntryType.ADJUSTMENT,null,null,"test:risk-monitor"));
    }
    void warningAccount() { fund(Asset.EUR,"1060.50");fund(Asset.XAU,"-10"); }
    String level() { return jdbc.queryForObject("SELECT level FROM trading_risk_monitor_state WHERE account_id=?",String.class,id); }
    int notifications() { return jdbc.queryForObject("SELECT COUNT(*) FROM trading_risk_monitor_event WHERE account_id=? AND delivery_status<>'AUDIT'",Integer.class,id); }

    @Test void installAndUpgradePreserveHistoryAndProtectAudit() {
        assertThat(jdbc.queryForList("SELECT * FROM databasechangelog WHERE orderexecuted<=12 ORDER BY orderexecuted")).isEqualTo(historical);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM databasechangelog",Integer.class)).isEqualTo(15);
        warningAccount();assertThat(service.observe(id)).isTrue();
        assertThatThrownBy(()->jdbc.update("UPDATE trading_risk_monitor_event SET reason='tampered' WHERE account_id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM trading_risk_monitor_event WHERE account_id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE trading_risk_monitor_state SET level='INVALID' WHERE account_id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE trading_risk_monitor_event SET delivery_status='SENDING' WHERE account_id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO trading_risk_monitor_event SELECT gen_random_uuid(),account_id,transition_sequence,old_level,new_level,indicators,price_as_of,created_at,reason,delivery_status,attempt_count,next_attempt_at,claim_token,lease_until,sent_at,last_error FROM trading_risk_monitor_event WHERE account_id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void noPositionNeedsNoPricesAndNoMail() {
        assertThat(service.observe(id)).isTrue();assertThat(level()).isEqualTo("NO_POSITION");assertThat(notifications()).isZero();
        verifyNoInteractions(provider);
    }

    @Test void demoAccountsNeverEnterLiveMonitorCandidates() {
        long demo=jdbc.queryForObject("INSERT INTO trading_account(company_id,base_currency,status,account_mode) VALUES (2,'EUR','ACTIVE','DEMO') RETURNING id",Long.class);
        assertThat(repository.allCandidates(0,100)).contains(id).doesNotContain(demo);
        assertThat(repository.candidates(0,100)).doesNotContain(demo);
        service.scan();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trading_risk_monitor_state WHERE account_id=?",Integer.class,demo)).isZero();
        verifyNoInteractions(provider);
    }
    @Test void enforcedMonitorDiscoversOfficialOnlyPositionAndAuditsUnavailableBalance() {
        jdbc.update("UPDATE trading_account SET as400_ste='B',as400_nucli_trading=20662 WHERE id=?",id);
        var official=mock(OfficialTradingBalanceReader.class);
        var amount=new EnumMap<Asset,BigDecimal>(Asset.class);
        for(var asset:List.of(Asset.EUR,Asset.XAU,Asset.XAG,Asset.XPT,Asset.XPD)) amount.put(asset,BigDecimal.ZERO);
        amount.put(Asset.EUR,b("1000"));amount.put(Asset.XAU,b("1"));
        when(official.read(any(),anyList())).thenReturn(new OfficialTradingBalanceReader.Reading(amount,Map.of()));
        var mode=new EffectiveBalanceProperties();mode.setMode(EffectiveBalanceProperties.Mode.ENFORCED);mode.setMaxSnapshotAge(java.time.Duration.ofMinutes(2));
        mode.setOverlayCutoverAt(java.time.Instant.parse("2020-01-01T00:00:00Z"));
        var pending=mock(PendingTradingAdjustmentRepository.class);
        when(pending.read(id)).thenReturn(List.of());
        var effective=new EffectiveBalanceService(balances,accounts,pending,official,mode);
        var monitor=new RiskMonitorService(repository,accounts,effective,context.getBean(PricingService.class),context.getBean(MarginRateRepository.class),context.getBean(RiskService.class),config,Clock.systemUTC());
        assertThat(balances.findAll(id)).isEmpty();
        monitor.scan();
        assertThat(level()).isEqualTo("NORMAL");
        assertThat(jdbc.queryForObject("SELECT indicators->>'positionValuation' FROM trading_risk_monitor_state WHERE account_id=?",String.class,id)).isEqualTo("99.00");
        when(official.read(any(),anyList())).thenThrow(new IllegalStateException("timeout"));
        monitor.scan();
        assertThat(jdbc.queryForObject("SELECT error_code FROM trading_risk_monitor_state WHERE account_id=?",String.class,id)).isEqualTo("OFFICIAL_BALANCE_UNAVAILABLE");
        assertThat(notifications()).isZero();
    }
    @Test void longShortAndMixedUseRealPricingAndMargin() {
        fund(Asset.EUR,"1000");fund(Asset.XAU,"10");
        assertThat(service.observe(id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT indicators->>'positionValuation' FROM trading_risk_monitor_state WHERE account_id=?",String.class,id)).isEqualTo("990.00");
        fund(Asset.XAG,"-10");assertThat(service.observe(id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT indicators->>'grossPosition' FROM trading_risk_monitor_state WHERE account_id=?",String.class,id)).isEqualTo("2000.00");
        assertThat(jdbc.queryForObject("SELECT indicators->>'positionValuation' FROM trading_risk_monitor_state WHERE account_id=?",String.class,id)).isEqualTo("-20.00");
        fund(Asset.XAU,"-10");assertThat(service.observe(id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT indicators->>'positionValuation' FROM trading_risk_monitor_state WHERE account_id=?",String.class,id)).isEqualTo("-1010.00");
    }
    @Test void generalSpecificAndMissingRatesAreRealRepositoryReads() {
        warningAccount();assertThat(service.observe(id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT indicators->>'marginRequirement' FROM trading_risk_monitor_state WHERE account_id=?",String.class,id)).isEqualTo("50.50");
        jdbc.update("INSERT INTO trading_margin_rate(account_id,asset,rate) VALUES (?,'XAU',0.1)",id);
        assertThat(service.observe(id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT indicators->>'marginRequirement' FROM trading_risk_monitor_state WHERE account_id=?",String.class,id)).isEqualTo("101.00");
        // Le schÃƒÆ’Ã‚Â©ma synthÃƒÆ’Ã‚Â©tique est rÃƒÆ’Ã‚Â©servÃƒÆ’Ã‚Â© ÃƒÆ’Ã‚Â  ce test ; aucune ligne d'une base applicative n'est visÃƒÆ’Ã‚Â©e.
        jdbc.update("DELETE FROM trading_margin_rate WHERE asset='XAU'");
        assertThat(service.observe(id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT error_code FROM trading_risk_monitor_state WHERE account_id=?",String.class,id)).isEqualTo("MARGIN_RATE_MISSING");
        assertThat(level()).isNotEqualTo("NORMAL").isNotEqualTo("PRICE_STALE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trading_risk_monitor_event WHERE reason='MARGIN_RATE_MISSING' AND account_id=?",Integer.class,id)).isEqualTo(1);
        service.observe(id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trading_risk_monitor_event WHERE reason='MARGIN_RATE_MISSING' AND account_id=?",Integer.class,id)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"absent","stale","future","oneOfSeveral"})
    void unavailablePriceNeverProducesHealthyResult(String mode) {
        warningAccount();
        if(mode.equals("oneOfSeveral")) fund(Asset.XAG,"1");
        String pair=mode.equals("oneOfSeveral")?"XAGEUR":"XAUEUR";
        if(mode.equals("absent")) jdbc.update("DELETE FROM trading_market_price WHERE pair=?",pair);
        else {
            Instant at=Instant.now().plusSeconds(mode.equals("future")?300:-300);
            market(pair.substring(0,3),at);
            when(provider.fetchSpotRates(anySet())).thenAnswer(i->prices.findAllMarketPrices().stream().map(p->new MarketQuote(p.pair(),p.bid(),p.ask(),p.mid(),p.priceAsOf())).toList());
            when(provider.sourceName()).thenReturn("TEST");
        }
        assertThat(service.observe(id)).isTrue();assertThat(level()).isEqualTo("PRICE_STALE");assertThat(notifications()).isEqualTo(1);
        assertThat(service.observe(id)).isTrue();assertThat(notifications()).isEqualTo(1);
        verify(provider,never()).submitSpotOrder(any());
    }
    @Test void staleExitAndReentryAndClosurePreserveFinancialEpisode() {
        warningAccount();service.observe(id);assertThat(notifications()).isEqualTo(1);
        jdbc.update("DELETE FROM trading_market_price WHERE pair='XAUEUR'");
        service.observe(id);service.observe(id);assertThat(notifications()).isEqualTo(2);
        market("XAU",Instant.now());service.observe(id);assertThat(notifications()).isEqualTo(2);
        jdbc.update("DELETE FROM trading_market_price WHERE pair='XAUEUR'");service.observe(id);assertThat(notifications()).isEqualTo(3);
        fund(Asset.XAU,"10");service.observe(id);assertThat(level()).isEqualTo("NO_POSITION");assertThat(notifications()).isEqualTo(3);
        assertThat(repository.candidates(0,10)).doesNotContain(id);
    }
    @Test void suspendedExposedAccountsAreSelectedWithoutCombiningAccounts() {
        warningAccount();jdbc.update("UPDATE trading_account SET status='SUSPENDED' WHERE id=?",id);
        long other=jdbc.queryForObject("INSERT INTO trading_account(company_id,base_currency,status) VALUES (2,'EUR','ACTIVE') RETURNING id",Long.class);
        assertThat(repository.candidates(0,10)).containsExactly(id).doesNotContain(other);
        assertThat(service.observe(id)).isTrue();assertThat(level()).isEqualTo("WARNING");
    }
    @Test void twoInstancesDecideOneEventAndRejectObsoleteObservation() throws Exception {
        warningAccount();
        var first=service.prepare(id);var second=service.prepare(id);
        var other=new RiskMonitorConfiguration().riskMonitorRepository(new JdbcTemplate(ds),context.getBean(DataSourceTransactionManager.class),accounts,new ObjectMapper().findAndRegisterModules());
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{ready.countDown();assertThat(go.await(5,TimeUnit.SECONDS)).isTrue();return repository.persist(first,config,Clock.systemUTC());});
            var b=pool.submit(()->{ready.countDown();assertThat(go.await(5,TimeUnit.SECONDS)).isTrue();return other.persist(second,config,Clock.systemUTC());});
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();go.countDown();
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(notifications()).isEqualTo(1);
        var stale=service.prepare(id);fund(Asset.EUR,"1");assertThat(repository.persist(stale,config,Clock.systemUTC())).isFalse();
    }
    @Test void stateAndEventRollbackTogether() {
        warningAccount();
        doThrow(new org.springframework.dao.DataIntegrityViolationException("injected")).when(jdbc).update(startsWith("INSERT INTO trading_risk_monitor_event"),any(Object[].class));
        assertThatThrownBy(()->service.observe(id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(repository.version(id)).isZero();assertThat(notifications()).isZero();
    }
    @Test void monitoringDoesNotWriteBusinessDataOrCallExternalBusiness() {
        warningAccount();
        Map<String,List<Map<String,Object>>> before=new HashMap<>();
        for(String table:List.of("trading_order","trading_balance","trading_reservation","trading_ledger_entry","trading_as400_sync_outbox"))
            before.put(table,jdbc.queryForList("SELECT * FROM "+table));
        service.observe(id);service.observe(id);
        before.forEach((table,rows)->assertThat(jdbc.queryForList("SELECT * FROM "+table)).isEqualTo(rows));
        verifyNoInteractions(provider);
        assertThat(context.getBeansOfType(com.saamp.trading.as400.As400SyncWorker.class)).isEmpty();
        assertThat(context.getBeansOfType(com.saamp.trading.order.OrderExecutionService.class)).isEmpty();
    }
    @Test void smtpFailureRetriesSameEventOutsideLocks() throws Exception {
        warningAccount();service.observe(id);
        JavaMailSender mail=mock(JavaMailSender.class);
        when(mail.createMimeMessage()).thenAnswer(i->new MimeMessage((jakarta.mail.Session)null));
        List<String> identifiers=new ArrayList<>();
        doAnswer(i->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            tx.executeWithoutResult(s->jdbc.queryForObject("SELECT id FROM trading_account WHERE id=? FOR UPDATE NOWAIT",Long.class,id));
            identifiers.add(i.<MimeMessage>getArgument(0).getHeader("Message-ID",null));
            if(identifiers.size()==1) throw new org.springframework.mail.MailSendException("synthetic failure");
            return null;
        }).when(mail).send(any(MimeMessage.class));
        var worker=new RiskMonitorMailWorker(repository,mail,config);
        worker.deliver();assertThat(notifications()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT delivery_status FROM trading_risk_monitor_event WHERE account_id=? AND delivery_status<>'AUDIT'",String.class,id)).isEqualTo("RETRY");
        service.observe(id);assertThat(notifications()).isEqualTo(1);
        jdbc.update("UPDATE trading_risk_monitor_event SET next_attempt_at=NOW()-INTERVAL '1 second' WHERE account_id=? AND delivery_status='RETRY'",id);
        worker.deliver();assertThat(identifiers).hasSize(2);assertThat(identifiers.get(0)).isEqualTo(identifiers.get(1));
        assertThat(jdbc.queryForObject("SELECT last_notified_level FROM trading_risk_monitor_state WHERE account_id=?",String.class,id)).isEqualTo("WARNING");
        assertThat(worker.backoff(100)).isEqualTo(config.retryMax());
    }
    @Test void claimsAreExclusiveAndExpiredTokenCannotAcknowledge() throws Exception {
        warningAccount();service.observe(id);
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        List<RiskMonitorRepository.Event> claimed=new ArrayList<>();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{ready.countDown();go.await(5,TimeUnit.SECONDS);return repository.claim(1,config.claimLease(),config.maxAttempts());});
            var b=pool.submit(()->{ready.countDown();go.await(5,TimeUnit.SECONDS);return repository.claim(1,config.claimLease(),config.maxAttempts());});
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();go.countDown();
            claimed.addAll(a.get(10,TimeUnit.SECONDS));claimed.addAll(b.get(10,TimeUnit.SECONDS));
        }
        assertThat(claimed).hasSize(1);var old=claimed.getFirst();
        var wrong=new RiskMonitorRepository.Event(old.id(),old.accountId(),old.sequence(),old.level(),old.indicators(),old.priceAsOf(),old.createdAt(),old.reason(),UUID.randomUUID(),old.attempts());
        assertThat(repository.renew(wrong,config.claimLease())).isFalse();
        assertThat(repository.renew(old,config.claimLease())).isTrue();
        jdbc.update("UPDATE trading_risk_monitor_event SET lease_until=NOW()-INTERVAL '1 second' WHERE id=?",old.id());
        assertThat(repository.renew(old,config.claimLease())).isFalse();
        assertThat(repository.acknowledge(old)).isFalse();
        var newer=repository.claim(1,config.claimLease(),config.maxAttempts()).getFirst();assertThat(newer.token()).isNotEqualTo(old.token());
        assertThat(repository.renew(old,config.claimLease())).isFalse();
        assertThat(repository.acknowledge(old)).isFalse();assertThat(repository.acknowledge(newer)).isTrue();
        assertThat(repository.acknowledge(newer)).isFalse();
    }
    @Test void skipLockedDoesNotWaitForOtherClaimAndRespectsLimit() throws Exception {
        warningAccount();service.observe(id);
        try(var c=ds.getConnection()) {
            c.setAutoCommit(false);
            try(var s=c.createStatement()) { s.executeQuery("SELECT id FROM trading_risk_monitor_event WHERE delivery_status='PENDING' FOR UPDATE"); }
            try(var pool=Executors.newSingleThreadExecutor()) {
                assertThat(pool.submit(()->repository.claim(1,config.claimLease(),config.maxAttempts())).get(3,TimeUnit.SECONDS)).isEmpty();
            } finally { c.rollback(); }
        }
        assertThat(repository.claim(1,config.claimLease(),config.maxAttempts())).hasSize(1);
    }
    @Test void permanentSmtpFailureStopsAfterDefaultAttemptLimit() throws Exception {
        warningAccount();service.observe(id);
        var mail=mock(JavaMailSender.class);
        when(mail.createMimeMessage()).thenAnswer(i->new MimeMessage((jakarta.mail.Session)null));
        doThrow(new org.springframework.mail.MailSendException("synthetic failure")).when(mail).send(any(MimeMessage.class));
        var worker=new RiskMonitorMailWorker(repository,mail,config);
        for(int i=0;i<5;i++) {
            worker.deliver();
            if(i<4) jdbc.update("UPDATE trading_risk_monitor_event SET next_attempt_at=NOW()-INTERVAL '1 second' WHERE account_id=? AND delivery_status='RETRY'",id);
        }
        assertThat(jdbc.queryForObject("SELECT delivery_status FROM trading_risk_monitor_event WHERE account_id=? AND delivery_status<>'AUDIT'",String.class,id)).isEqualTo("FAILED");
        worker.deliver();verify(mail,times(5)).send(any(MimeMessage.class));
        assertThat(notifications()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT next_attempt_at IS NULL AND claim_token IS NULL AND lease_until IS NULL AND sent_at IS NULL FROM trading_risk_monitor_event WHERE account_id=? AND delivery_status='FAILED'",Boolean.class,id)).isTrue();
    }

    @Test void singleAttemptFailsPermanentlyWithoutChangingAudit() throws Exception {
        warningAccount();service.observe(id);
        var before=jdbc.queryForList("SELECT id,account_id,transition_sequence,old_level,new_level,indicators,price_as_of,created_at,reason FROM trading_risk_monitor_event WHERE account_id=?",id);
        var c=config;
        var once=new RiskMonitorProperties(c.interval(),c.maxPriceAge(),c.hysteresisPoints(),c.rearmDuration(),c.maxObservationGap(),c.batchSize(),c.claimLease(),c.retryInitial(),c.retryMax(),c.smtp(),c.warningThreshold(),c.criticalThreshold(),c.liquidationRequiredThreshold(),1);
        var mail=mock(JavaMailSender.class);
        when(mail.createMimeMessage()).thenAnswer(i->new MimeMessage((jakarta.mail.Session)null));
        doThrow(new org.springframework.mail.MailSendException("synthetic failure")).when(mail).send(any(MimeMessage.class));
        var worker=new RiskMonitorMailWorker(repository,mail,once);worker.deliver();worker.deliver();
        verify(mail,times(1)).send(any(MimeMessage.class));
        assertThat(jdbc.queryForObject("SELECT delivery_status FROM trading_risk_monitor_event WHERE account_id=? AND delivery_status<>'AUDIT'",String.class,id)).isEqualTo("FAILED");
        assertThat(jdbc.queryForList("SELECT id,account_id,transition_sequence,old_level,new_level,indicators,price_as_of,created_at,reason FROM trading_risk_monitor_event WHERE account_id=?",id)).isEqualTo(before);
        assertThat(repository.claim(1,c.claimLease(),1)).isEmpty();
        service.observe(id);assertThat(notifications()).isEqualTo(1);
        assertThatThrownBy(()->jdbc.update("UPDATE trading_risk_monitor_event SET delivery_status='PENDING',next_attempt_at=NOW() WHERE account_id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test void lastAttemptIsExclusiveAndDisappearedWorkerEndsFailed() throws Exception {
        warningAccount();service.observe(id);
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        var other=new RiskMonitorConfiguration().riskMonitorRepository(new JdbcTemplate(ds),context.getBean(DataSourceTransactionManager.class),accounts,new ObjectMapper().findAndRegisterModules());
        List<RiskMonitorRepository.Event> claimed=new ArrayList<>();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{ready.countDown();assertThat(go.await(5,TimeUnit.SECONDS)).isTrue();return repository.claim(1,config.claimLease(),1);});
            var b=pool.submit(()->{ready.countDown();assertThat(go.await(5,TimeUnit.SECONDS)).isTrue();return other.claim(1,config.claimLease(),1);});
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();go.countDown();
            claimed.addAll(a.get(10,TimeUnit.SECONDS));claimed.addAll(b.get(10,TimeUnit.SECONDS));
        }
        assertThat(claimed).hasSize(1);var abandoned=claimed.getFirst();
        jdbc.update("UPDATE trading_risk_monitor_event SET lease_until=NOW()-INTERVAL '1 second' WHERE id=?",abandoned.id());
        assertThat(other.claim(1,config.claimLease(),1)).isEmpty();
        assertThat(repository.renew(abandoned,config.claimLease())).isFalse();
        assertThat(repository.acknowledge(abandoned)).isFalse();
        assertThat(jdbc.queryForObject("SELECT delivery_status FROM trading_risk_monitor_event WHERE id=?",String.class,abandoned.id())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM trading_risk_monitor_event WHERE id=?",Integer.class,abandoned.id())).isEqualTo(1);
        assertThat(notifications()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void transientHeartbeatStillAcknowledgesSuccessfulSmtp(boolean awaitRecovery) throws Exception {
        warningAccount();service.observe(id);
        var transientFailure=new CountDownLatch(1);var recovered=new CountDownLatch(1);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var observed=spy(repository);
        doAnswer(invocation->{
            int call=calls.incrementAndGet();
            if(call==2) {
                transientFailure.countDown();
                throw new org.springframework.dao.TransientDataAccessResourceException("synthetic heartbeat unavailable");
            }
            boolean renewed=(boolean)invocation.callRealMethod();
            if(call>2 && renewed) recovered.countDown();
            return renewed;
        }).when(observed).renew(any(),any());
        var smtp=new RiskMonitorProperties.Smtp("localhost",2525,null,null,"monitor@example.invalid",
                List.of("recipient@example.invalid"),false,false,Duration.ofMillis(100));
        var local=new RiskMonitorProperties(config.interval(),config.maxPriceAge(),config.hysteresisPoints(),
                config.rearmDuration(),config.maxObservationGap(),1,Duration.ofSeconds(6),config.retryInitial(),config.retryMax(),smtp);
        var mail=mock(JavaMailSender.class);
        when(mail.createMimeMessage()).thenAnswer(i->new MimeMessage((jakarta.mail.Session)null));
        try(var timer=Executors.newSingleThreadScheduledExecutor()) {
            doAnswer(invocation->{
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(transientFailure.await(8,TimeUnit.SECONDS)).isTrue();
                if(awaitRecovery) assertThat(recovered.await(5,TimeUnit.SECONDS)).isTrue();
                else {
                    var completed=new CountDownLatch(1);
                    timer.schedule(completed::countDown,200,TimeUnit.MILLISECONDS);
                    assertThat(completed.await(2,TimeUnit.SECONDS)).isTrue();
                }
                assertThat(jdbc.queryForObject("SELECT lease_until>clock_timestamp() FROM trading_risk_monitor_event WHERE account_id=?",Boolean.class,id)).isTrue();
                return null;
            }).when(mail).send(any(MimeMessage.class));
            new RiskMonitorMailWorker(observed,mail,local).deliver();
        }
        assertThat(jdbc.queryForObject("SELECT delivery_status FROM trading_risk_monitor_event WHERE account_id=?",String.class,id)).isEqualTo("SENT");
        verify(observed).acknowledge(any());
        verify(mail,times(1)).send(any(MimeMessage.class));
        var otherMail=mock(JavaMailSender.class);
        var other=new RiskMonitorConfiguration().riskMonitorRepository(new JdbcTemplate(ds),context.getBean(DataSourceTransactionManager.class),accounts,new ObjectMapper().findAndRegisterModules());
        new RiskMonitorMailWorker(other,otherMail,local).deliver();
        verify(otherMail,never()).send(any(MimeMessage.class));
        assertThat(notifications()).isEqualTo(1);
    }

    @Test void expiredClaimCannotBeRenewedAcknowledgedOrRetriedByOldWorker() throws Exception {
        warningAccount();service.observe(id);
        var old=repository.claim(1,Duration.ofMillis(300),5).getFirst();
        try(var timer=Executors.newSingleThreadScheduledExecutor()) {
            var elapsed=new CountDownLatch(1);timer.schedule(elapsed::countDown,600,TimeUnit.MILLISECONDS);
            assertThat(elapsed.await(3,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT lease_until<=clock_timestamp() FROM trading_risk_monitor_event WHERE id=?",Boolean.class,old.id())).isTrue();
        assertThat(repository.renew(old,config.claimLease())).isFalse();
        var other=new RiskMonitorConfiguration().riskMonitorRepository(new JdbcTemplate(ds),context.getBean(DataSourceTransactionManager.class),accounts,new ObjectMapper().findAndRegisterModules());
        var reclaimed=other.claim(1,config.claimLease(),5).getFirst();
        assertThat(reclaimed.token()).isNotEqualTo(old.token());
        var before=jdbc.queryForList("SELECT * FROM trading_risk_monitor_event WHERE id=?",old.id());
        assertThat(repository.acknowledge(old)).isFalse();
        repository.retry(old,Duration.ofSeconds(1),1);
        assertThat(repository.renew(old,config.claimLease())).isFalse();
        assertThat(jdbc.queryForList("SELECT * FROM trading_risk_monitor_event WHERE id=?",old.id())).isEqualTo(before);
        assertThat(other.acknowledge(reclaimed)).isTrue();
    }

    @Test void successfulSmtpWithUnavailableAcknowledgementLeavesClaimForRecovery() throws Exception {
        warningAccount();service.observe(id);
        var observed=spy(repository);
        doThrow(new org.springframework.dao.TransientDataAccessResourceException("synthetic acknowledgement unavailable"))
                .when(observed).acknowledge(any());
        var mail=mock(JavaMailSender.class);
        when(mail.createMimeMessage()).thenAnswer(i->new MimeMessage((jakarta.mail.Session)null));
        new RiskMonitorMailWorker(observed,mail,config).deliver();
        verify(mail,times(1)).send(any(MimeMessage.class));
        verify(observed).acknowledge(any());
        verify(observed,never()).retry(any(),any(),anyInt());
        assertThat(jdbc.queryForObject("SELECT delivery_status FROM trading_risk_monitor_event WHERE account_id=?",String.class,id)).isEqualTo("SENDING");
        assertThat(notifications()).isEqualTo(1);
    }

    @Test void slowSmtpKeepsClaimAcrossInitialLeaseWithTwoWorkers() throws Exception {
        warningAccount();service.observe(id);
        var reached=new CountDownLatch(1);var checked=new CountDownLatch(1);
        var originalToken=new java.util.concurrent.atomic.AtomicReference<UUID>();
        var originalLease=new java.util.concurrent.atomic.AtomicReference<java.sql.Timestamp>();
        try(var server=new java.net.ServerSocket(0,2,java.net.InetAddress.getLoopbackAddress());
                var pool=Executors.newFixedThreadPool(2);var delays=Executors.newSingleThreadScheduledExecutor()) {
            server.setSoTimeout(10000);
            var received=pool.submit(()->{
                int messages=0;
                try(var socket=server.accept()) {
                    originalToken.set(jdbc.queryForObject("SELECT claim_token FROM trading_risk_monitor_event WHERE account_id=? AND delivery_status='SENDING'",UUID.class,id));
                    originalLease.set(jdbc.queryForObject("SELECT lease_until FROM trading_risk_monitor_event WHERE account_id=? AND delivery_status='SENDING'",java.sql.Timestamp.class,id));
                    socket.setSoTimeout(5000);
                    var reader=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),java.nio.charset.StandardCharsets.UTF_8));
                    var writer=new java.io.PrintWriter(socket.getOutputStream(),true,java.nio.charset.StandardCharsets.UTF_8);
                    writer.print("220 localhost test SMTP\r\n");writer.flush();int recipients=0;
                    String line;
                    while((line=reader.readLine())!=null) {
                        if(line.startsWith("RCPT")) {
                            var step=new CountDownLatch(1);delays.schedule(step::countDown,400,TimeUnit.MILLISECONDS);
                            assertThat(step.await(2,TimeUnit.SECONDS)).isTrue();
                            if(++recipients==20) {reached.countDown();assertThat(checked.await(2,TimeUnit.SECONDS)).isTrue();}
                            writer.print("250 recipient accepted\r\n");writer.flush();
                        } else if(line.equals("DATA")) {
                            writer.print("354 send data\r\n");writer.flush();
                            while((line=reader.readLine())!=null && !line.equals(".")) { }
                            messages++;writer.print("250 accepted\r\n");writer.flush();
                        } else if(line.equals("QUIT")) {writer.print("221 bye\r\n");writer.flush();break;}
                        else {writer.print("250 localhost\r\n");writer.flush();}
                    }
                }
                return messages;
            });
            var recipients=java.util.stream.IntStream.range(0,20).mapToObj(i->"recipient"+i+"@example.invalid").toList();
            var smtp=new RiskMonitorProperties.Smtp("localhost",server.getLocalPort(),null,null,"monitor@example.invalid",recipients,false,false,Duration.ofSeconds(2));
            var local=new RiskMonitorProperties(config.interval(),config.maxPriceAge(),config.hysteresisPoints(),config.rearmDuration(),config.maxObservationGap(),1,Duration.ofSeconds(7),config.retryInitial(),config.retryMax(),smtp);
            var first=new RiskMonitorConfiguration().riskMonitorMailWorker(repository,local);
            var otherMail=mock(JavaMailSender.class);
            when(otherMail.createMimeMessage()).thenAnswer(i->new MimeMessage((jakarta.mail.Session)null));
            var otherRepository=new RiskMonitorConfiguration().riskMonitorRepository(new JdbcTemplate(ds),context.getBean(DataSourceTransactionManager.class),accounts,new ObjectMapper().findAndRegisterModules());
            var second=new RiskMonitorMailWorker(otherRepository,otherMail,local);
            var delivery=pool.submit(first::deliver);
            try {
                assertThat(reached.await(15,TimeUnit.SECONDS)).isTrue();
                second.deliver();
                assertThat(jdbc.queryForObject("SELECT clock_timestamp()>?",Boolean.class,originalLease.get())).isTrue();
                assertThat(jdbc.queryForObject("SELECT claim_token FROM trading_risk_monitor_event WHERE account_id=?",UUID.class,id)).isEqualTo(originalToken.get());
                assertThat(jdbc.queryForObject("SELECT lease_until>clock_timestamp() FROM trading_risk_monitor_event WHERE account_id=?",Boolean.class,id)).isTrue();
            } finally {checked.countDown();}
            delivery.get(10,TimeUnit.SECONDS);
            assertThat(received.get(10,TimeUnit.SECONDS)).isEqualTo(1);
            verify(otherMail,never()).send(any(MimeMessage.class));
            assertThat(jdbc.queryForObject("SELECT attempt_count FROM trading_risk_monitor_event WHERE account_id=? AND delivery_status='SENT'",Integer.class,id)).isEqualTo(1);
        }
    }

    @Test void smtpProtocolLostResponseRetriesStableMessageId() throws Exception {
        warningAccount();service.observe(id);
        try(var server=new java.net.ServerSocket(0,2,java.net.InetAddress.getLoopbackAddress());var pool=Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(10000);
            var received=pool.submit(()->{
                List<String> ids=new ArrayList<>();
                for(int attempt=0;attempt<2;attempt++) try(var socket=server.accept()) {
                    socket.setSoTimeout(5000);
                    var reader=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),java.nio.charset.StandardCharsets.UTF_8));
                    var writer=new java.io.PrintWriter(socket.getOutputStream(),true,java.nio.charset.StandardCharsets.UTF_8);
                    writer.print("220 localhost test SMTP\r\n");writer.flush();
                    String line;
                    while((line=reader.readLine())!=null) {
                        if(line.equals("DATA")) {
                            writer.print("354 send data\r\n");writer.flush();
                            while((line=reader.readLine())!=null && !line.equals("."))
                                if(line.startsWith("Message-ID:")) ids.add(line.substring(11).trim());
                            if(attempt==0) break; // Message acceptÃƒÆ’Ã‚Â© par le double, rÃƒÆ’Ã‚Â©ponse perdue : ambiguÃƒÆ’Ã‚Â¯tÃƒÆ’Ã‚Â© SMTP assumÃƒÆ’Ã‚Â©e.
                            writer.print("250 accepted\r\n");writer.flush();
                        } else if(line.equals("QUIT")) { writer.print("221 bye\r\n");writer.flush();break; }
                        else { writer.print("250 localhost\r\n");writer.flush(); }
                    }
                }
                return ids;
            });
            var smtp=new RiskMonitorProperties.Smtp("localhost",server.getLocalPort(),null,null,"monitor@example.invalid",List.of("recipient@example.invalid"),false,false,Duration.ofSeconds(1));
            var local=new RiskMonitorProperties(config.interval(),config.maxPriceAge(),config.hysteresisPoints(),config.rearmDuration(),config.maxObservationGap(),1,config.claimLease(),config.retryInitial(),config.retryMax(),smtp);
            var worker=new RiskMonitorConfiguration().riskMonitorMailWorker(repository,local);
            worker.deliver();
            assertThat(jdbc.queryForObject("SELECT delivery_status FROM trading_risk_monitor_event WHERE account_id=? AND delivery_status<>'AUDIT'",String.class,id)).isEqualTo("RETRY");
            jdbc.update("UPDATE trading_risk_monitor_event SET next_attempt_at=NOW()-INTERVAL '1 second' WHERE account_id=? AND delivery_status='RETRY'",id);
            worker.deliver();
            var ids=received.get(10,TimeUnit.SECONDS);assertThat(ids).hasSize(2);assertThat(ids.get(0)).isEqualTo(ids.get(1));
            assertThat(jdbc.queryForObject("SELECT delivery_status FROM trading_risk_monitor_event WHERE account_id=? AND delivery_status<>'AUDIT'",String.class,id)).isEqualTo("SENT");
        }
    }
    @Test void rearmIsPersistedAcrossServiceInstances() {
        warningAccount();
        var instant=new java.util.concurrent.atomic.AtomicReference<>(Instant.now());
        Clock clock=new Clock() {
            public java.time.ZoneId getZone(){return ZoneOffset.UTC;}
            public Clock withZone(java.time.ZoneId zone){return this;}
            public Instant instant(){return instant.get();}
        };
        var monitor=new RiskMonitorService(repository,accounts,context.getBean(EffectiveBalanceService.class),context.getBean(PricingService.class),context.getBean(MarginRateRepository.class),context.getBean(RiskService.class),config,clock);
        monitor.observe(id);assertThat(notifications()).isEqualTo(1);
        fund(Asset.EUR,"5");
        for(int i=0;i<4;i++) { instant.set(instant.get().plusSeconds(4));assertThat(monitor.observe(id)).isTrue(); }
        assertThat(notifications()).isEqualTo(1);
        fund(Asset.EUR,"-5");instant.set(instant.get().plusSeconds(1));
        new RiskMonitorService(repository,accounts,context.getBean(EffectiveBalanceService.class),context.getBean(PricingService.class),context.getBean(MarginRateRepository.class),context.getBean(RiskService.class),config,clock).observe(id);
        assertThat(notifications()).isEqualTo(2);
    }

    @Test void freshnessIsCheckedAfterWaitingForAccountLock() throws Exception {
        warningAccount();var observation=service.prepare(id);
        var now=new java.util.concurrent.atomic.AtomicReference<>(Instant.now());
        Clock clock=new Clock() {
            public java.time.ZoneId getZone(){return ZoneOffset.UTC;}
            public Clock withZone(java.time.ZoneId zone){return this;}
            public Instant instant(){
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                return now.get();
            }
        };
        var attempted=new CountDownLatch(1);
        doAnswer(call->{ attempted.countDown();return call.callRealMethod(); }).when(jdbc)
                .query(eq("SELECT * FROM trading_account WHERE id=? FOR UPDATE"),any(org.springframework.jdbc.core.RowMapper.class),any(Object[].class));
        try(var connection=ds.getConnection();var statement=connection.createStatement();var pool=Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            statement.executeQuery("SELECT id FROM trading_account WHERE id="+id+" FOR UPDATE");
            var decision=pool.submit(()->repository.persist(observation,config,clock));
            try {
                assertThat(attempted.await(3,TimeUnit.SECONDS)).isTrue();
                now.set(now.get().plusSeconds(120));
            } finally { connection.rollback(); }
            assertThat(decision.get(10,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(level()).isEqualTo("PRICE_STALE");
        assertThat(jdbc.queryForObject("SELECT indicators FROM trading_risk_monitor_state WHERE account_id=?",String.class,id)).isNull();
        assertThat(jdbc.queryForObject("SELECT created_at FROM trading_risk_monitor_event WHERE account_id=?",java.sql.Timestamp.class,id).toInstant()).isAfterOrEqualTo(now.get().minusMillis(1));
    }

}
