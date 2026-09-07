package com.saamp.trading.integration;

import com.saamp.trading.account.BalanceRepository;
import com.saamp.trading.as400.As400SyncOutboxRepository;
import com.saamp.trading.common.TradingException;
import com.saamp.trading.config.TradingProperties;
import com.saamp.trading.domain.Asset;
import com.saamp.trading.ledger.LedgerRepository;
import com.saamp.trading.ledger.LedgerService;
import com.saamp.trading.reservation.ReservationRepository;
import com.saamp.trading.reservation.ReservationService;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TradingDatabaseInvariantTest {

    private static final String DATABASE_URL = "jdbc:postgresql://localhost:5432/trading";
    private static final String DATABASE_USERNAME = "trading";
    private static final String DATABASE_PASSWORD_ENVIRONMENT_VARIABLE = "TRADING_DB_PASSWORD";
    private static final AtomicLong COMPANY_IDS = new AtomicLong(System.currentTimeMillis());

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private ReservationService reservations;
    private FailingSettlement failingSettlement;
    private As400SyncOutboxRepository as400Outbox;
    private TransactionTemplate transactions;

    @BeforeAll
    void startDatabase() {
        verifyLocalDatabaseIsAvailable();
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        jdbc = context.getBean(JdbcTemplate.class);
        reservations = context.getBean(ReservationService.class);
        failingSettlement = context.getBean(FailingSettlement.class);
        as400Outbox = context.getBean(As400SyncOutboxRepository.class);
        transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    @AfterAll
    void stopDatabase() {
        if (context != null) context.close();
    }

    @Test
    void twoConcurrentReservationsOnSameBalanceAllowOnlyOne() throws Exception {
        long companyId = COMPANY_IDS.incrementAndGet();
        long accountId = createAccount(companyId);
        jdbc.update("INSERT INTO trading_balance(account_id,asset,quantity) VALUES (?,?,?)", accountId, "EUR", new BigDecimal("100.000000"));
        long order1 = createDraftOrder(accountId, companyId, "concurrent-1-" + companyId);
        long order2 = createDraftOrder(accountId, companyId, "concurrent-2-" + companyId);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<Boolean>> calls = List.of(
                    () -> reserveAfterBarrier(ready, start, accountId, order1),
                    () -> reserveAfterBarrier(ready, start, accountId, order2));
            List<Future<Boolean>> futures = new ArrayList<>();
            for (Callable<Boolean> call : calls) futures.add(executor.submit(call));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            long successes = 0;
            for (Future<Boolean> future : futures) if (future.get(15, TimeUnit.SECONDS)) successes++;

            assertThat(successes).isEqualTo(1);
            BigDecimal active = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(quantity),0) FROM trading_reservation WHERE account_id=? AND asset='EUR' AND status='ACTIVE'",
                    BigDecimal.class, accountId);
            assertThat(active).isEqualByComparingTo("80.000000");
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void databaseTriggerRejectsNegativeCurrencyBalanceEvenWhenServiceIsBypassed() {
        long accountId = createAccount(COMPANY_IDS.incrementAndGet());
        jdbc.update("INSERT INTO trading_balance(account_id,asset,quantity) VALUES (?,?,?)", accountId, "EUR", new BigDecimal("10.000000"));

        assertThatThrownBy(() -> jdbc.update("UPDATE trading_balance SET quantity=-0.000001 WHERE account_id=? AND asset='EUR'", accountId))
                .isInstanceOf(DataAccessException.class);

        BigDecimal quantity = jdbc.queryForObject("SELECT quantity FROM trading_balance WHERE account_id=? AND asset='EUR'", BigDecimal.class, accountId);
        assertThat(quantity).isEqualByComparingTo("10.000000");
    }

    @Test
    void ledgerUpdateIsRejectedByAppendOnlyTrigger() {
        long accountId = createAccount(COMPANY_IDS.incrementAndGet());
        jdbc.update("INSERT INTO trading_balance(account_id,asset,quantity) VALUES (?,?,?)", accountId, "EUR", new BigDecimal("11.000000"));
        Long ledgerId = jdbc.queryForObject("""
                INSERT INTO trading_ledger_entry(account_id,asset,delta,entry_type,balance_after,created_by)
                VALUES (?,'EUR',1.000000,'ADJUSTMENT',11.000000,'test') RETURNING id
                """, Long.class, accountId);

        assertThatThrownBy(() -> jdbc.update("UPDATE trading_ledger_entry SET delta=2 WHERE id=?", ledgerId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void ledgerDeleteIsRejectedByAppendOnlyTrigger() {
        long accountId = createAccount(COMPANY_IDS.incrementAndGet());
        jdbc.update("INSERT INTO trading_balance(account_id,asset,quantity) VALUES (?,?,?)", accountId, "EUR", new BigDecimal("11.000000"));
        Long ledgerId = jdbc.queryForObject("""
                INSERT INTO trading_ledger_entry(account_id,asset,delta,entry_type,balance_after,created_by)
                VALUES (?,'EUR',1.000000,'ADJUSTMENT',11.000000,'test') RETURNING id
                """, Long.class, accountId);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM trading_ledger_entry WHERE id=?", ledgerId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void filledSettlementRollsBackLedgerBalancesAndOutboxAsOneTransaction() {
        long companyId = COMPANY_IDS.incrementAndGet();
        long accountId = createAccount(companyId);
        long orderId = createDraftOrder(accountId, companyId, "atomic-settlement-" + companyId);
        jdbc.update("INSERT INTO trading_balance(account_id,asset,quantity) VALUES (?,'XAU',10.000000)", accountId);
        jdbc.update("INSERT INTO trading_balance(account_id,asset,quantity) VALUES (?,'EUR',1000.000000)", accountId);

        assertThatThrownBy(() -> failingSettlement.postThenFail(accountId, orderId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("failure after ledger and outbox");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trading_ledger_entry WHERE order_id=? AND entry_type='TRADE'", Integer.class, orderId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trading_as400_sync_outbox WHERE order_id=?", Integer.class, orderId)).isZero();
        assertThat(jdbc.queryForObject("SELECT quantity FROM trading_balance WHERE account_id=? AND asset='XAU'", BigDecimal.class, accountId)).isEqualByComparingTo("10.000000");
        assertThat(jdbc.queryForObject("SELECT quantity FROM trading_balance WHERE account_id=? AND asset='EUR'", BigDecimal.class, accountId)).isEqualByComparingTo("1000.000000");
    }

    @Test
    void twoSuccessiveClaimsCannotAcquireTheSameActiveOutboxEvent() {
        transactions.executeWithoutResult(transaction -> {
            long companyId = COMPANY_IDS.incrementAndGet();
            long accountId = createAccount(companyId);
            long orderId = createDraftOrder(accountId, companyId, "outbox-claim-" + companyId);
            jdbc.update("UPDATE trading_as400_sync_outbox SET next_attempt_at=NOW()+INTERVAL '1 hour' WHERE status<>'SYNCED'");
            as400Outbox.enqueueFilled(orderId);

            var firstClaim = as400Outbox.claimDue(1, Duration.ofMinutes(5));
            var secondClaim = as400Outbox.claimDue(1, Duration.ofMinutes(5));

            assertThat(firstClaim).hasSize(1);
            assertThat(firstClaim.getFirst().orderId()).isEqualTo(orderId);
            assertThat(secondClaim).isEmpty();
            transaction.setRollbackOnly();
        });
    }

    @Test
    void liquibaseAppliedChangelogs001To009AndCriticalTriggersAreActive() {
        Integer changelogCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM databasechangelog
                WHERE id IN ('001-trading-core','002-trading-pricing','003-trading-defaults','004-trading-reconciliation',
                  '005-pmxconnect-corrections','006-as400-synchronization','007-as400-ste-width','008-as400-outbox-claim','009-as400-sicouvi')
                """, Integer.class);
        assertThat(changelogCount).isEqualTo(9);

        List<String> triggers = jdbc.queryForList("""
                SELECT tgname FROM pg_trigger
                WHERE NOT tgisinternal
                  AND tgname IN ('trg_trading_balance_currency_non_negative','trg_trading_ledger_append_only')
                ORDER BY tgname
                """, String.class);
        assertThat(triggers).containsExactly(
                "trg_trading_balance_currency_non_negative",
                "trg_trading_ledger_append_only");

        Integer providerTable = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name='trading_provider_account'", Integer.class);
        assertThat(providerTable).isEqualTo(1);
    }


    @Test
    void sicouiAndAccountIdentitySurviveRetryAndReclaim() {
        transactions.executeWithoutResult(transaction -> {
            long companyId=COMPANY_IDS.incrementAndGet();
            long accountId=createAccount(companyId);
            jdbc.update("UPDATE trading_account SET as400_ste='i',as400_nucli_commercial=123,as400_nucli_trading=456 WHERE id=?",accountId);
            long orderId=createDraftOrder(accountId,companyId,"sicoui-"+companyId);
            jdbc.update("UPDATE trading_as400_sync_outbox SET next_attempt_at=NOW()+INTERVAL '1 hour'");
            as400Outbox.enqueueFilled(orderId);
            as400Outbox.enqueueFilled(orderId);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trading_as400_sync_outbox WHERE order_id=?",Integer.class,orderId)).isEqualTo(1);
            var first=as400Outbox.claimDue(1,Duration.ofMinutes(5)).getFirst();
            assertThat(first.sicoui()).isBetween(1,999999);
            assertThat(first.ste()).isEqualTo("i");
            assertThat(first.nucliTrading()).isEqualTo(456);
            as400Outbox.markFailed(first,"AS400_TEMPORARY_TEST",false);
            jdbc.update("UPDATE trading_account SET as400_nucli_trading=789 WHERE id=?",accountId);
            jdbc.update("UPDATE trading_as400_sync_outbox SET next_attempt_at=NOW()-INTERVAL '1 second' WHERE id=?",first.id());
            var retry=as400Outbox.claimDue(1,Duration.ofMinutes(5)).getFirst();
            assertThat(retry.sicoui()).isEqualTo(first.sicoui());
            assertThat(retry.nucliTrading()).isEqualTo(456);
            assertThat(retry.attemptCount()).isEqualTo(1);
            jdbc.update("UPDATE trading_as400_sync_outbox SET next_attempt_at=NOW()-INTERVAL '1 second' WHERE id=?",first.id());
            var reclaimed=as400Outbox.claimDue(1,Duration.ofMinutes(5)).getFirst();
            assertThat(reclaimed.claimToken()).isNotEqualTo(retry.claimToken());
            assertThat(reclaimed.sicoui()).isEqualTo(first.sicoui());
            as400Outbox.advance(retry,com.saamp.trading.as400.As400SyncState.SETTLED,904736,Duration.ofHours(1));
            as400Outbox.markFailed(retry,"stale",true);
            as400Outbox.withClaim(retry,ignored -> { throw new AssertionError("stale claim executed"); });
            assertThat(jdbc.queryForObject("SELECT status FROM trading_as400_sync_outbox WHERE id=?",String.class,first.id())).isEqualTo("PROCESSING");
            as400Outbox.advance(reclaimed,com.saamp.trading.as400.As400SyncState.SUBMITTED,null,Duration.ofMinutes(5));
            assertThat(jdbc.queryForObject("SELECT submitted_at IS NOT NULL FROM trading_as400_sync_outbox WHERE id=?",Boolean.class,first.id())).isTrue();
            transaction.setRollbackOnly();
        });
    }

    @Test
    void submittedAndAcceptedProgressSurvivesNewRepositoryAndTransientFailures() {
        transactions.executeWithoutResult(transaction -> {
            long companyId=COMPANY_IDS.incrementAndGet();
            long accountId=createAccount(companyId);
            long orderId=createDraftOrder(accountId,companyId,"progress-"+companyId);
            jdbc.update("UPDATE trading_as400_sync_outbox SET next_attempt_at=NOW()+INTERVAL '1 hour'");
            as400Outbox.enqueueFilled(orderId);
            var event=as400Outbox.claimDue(1,Duration.ofMinutes(5)).getFirst();
            as400Outbox.advance(event,com.saamp.trading.as400.As400SyncState.SUBMITTED,null,Duration.ofMinutes(5));
            assertThat(jdbc.queryForObject("SELECT status FROM trading_as400_sync_outbox WHERE id=?",String.class,event.id())).isEqualTo("PENDING");
            jdbc.update("UPDATE trading_as400_sync_outbox SET next_attempt_at=NOW()-INTERVAL '1 second' WHERE id=?",event.id());
            var restarted=new As400SyncOutboxRepository(jdbc);
            var submitted=restarted.claimDue(1,Duration.ofMinutes(5)).getFirst();
            assertThat(submitted.state()).isEqualTo(com.saamp.trading.as400.As400SyncState.SUBMITTED);
            assertThat(jdbc.queryForObject("SELECT status FROM trading_as400_sync_outbox WHERE id=?",String.class,event.id())).isEqualTo("PROCESSING");
            restarted.advance(submitted,com.saamp.trading.as400.As400SyncState.ACCEPTED,904736,Duration.ofHours(1));
            assertThat(jdbc.queryForObject("SELECT status FROM trading_as400_sync_outbox WHERE id=?",String.class,event.id())).isEqualTo("PENDING");
            jdbc.update("UPDATE trading_as400_sync_outbox SET next_attempt_at=NOW()-INTERVAL '1 second' WHERE id=?",event.id());
            var accepted=restarted.claimDue(1,Duration.ofMinutes(5)).getFirst();
            assertThat(accepted.state()).isEqualTo(com.saamp.trading.as400.As400SyncState.ACCEPTED);
            assertThat(accepted.siprov()).isEqualTo(904736);
            restarted.markFailed(accepted,"AS400_TEMPORARY_TEST",false);
            assertThat(jdbc.queryForObject("SELECT status FROM trading_as400_sync_outbox WHERE id=?",String.class,event.id())).isEqualTo("RETRY");
            assertThat(jdbc.queryForObject("SELECT sync_state FROM trading_as400_sync_outbox WHERE id=?",String.class,event.id())).isEqualTo("ACCEPTED");
            assertThat(jdbc.queryForObject("SELECT accepted_at IS NOT NULL AND submitted_at IS NOT NULL AND siprov=904736 FROM trading_as400_sync_outbox WHERE id=?",Boolean.class,event.id())).isTrue();
            jdbc.update("UPDATE trading_as400_sync_outbox SET next_attempt_at=NOW()-INTERVAL '1 second' WHERE id=?",event.id());
            var finalClaim=restarted.claimDue(1,Duration.ofMinutes(5)).getFirst();
            restarted.advance(finalClaim,com.saamp.trading.as400.As400SyncState.SETTLED,904736,Duration.ofHours(1));
            assertThat(jdbc.queryForObject("SELECT status='SYNCED' AND synced_at IS NOT NULL AND settled_at IS NOT NULL AND last_error IS NULL AND claim_token IS NULL FROM trading_as400_sync_outbox WHERE id=?",Boolean.class,event.id())).isTrue();
            assertThat(restarted.claimDue(1,Duration.ofMinutes(5))).isEmpty();
            transaction.setRollbackOnly();
        });
    }

    @Test
    void skipLockedLetsAnotherInstanceContinueWithoutTakingAnActiveEvent() {
        long companyId=COMPANY_IDS.incrementAndGet();
        long accountId=createAccount(companyId);
        long orderId=createDraftOrder(accountId,companyId,"skip-locked-"+companyId);
        as400Outbox.enqueueFilled(orderId);
        try {
            transactions.executeWithoutResult(transaction -> {
                jdbc.update("UPDATE trading_as400_sync_outbox SET next_attempt_at=NOW()+INTERVAL '1 hour' WHERE order_id<>?",orderId);
                var claimed=as400Outbox.claimDue(1,Duration.ofMinutes(5));
                assertThat(claimed).hasSize(1);
                assertThat(claimed.getFirst().orderId()).isEqualTo(orderId);
                try {
                    var other=CompletableFuture.supplyAsync(()->as400Outbox.claimDue(50,Duration.ofMinutes(5)))
                            .get(5,TimeUnit.SECONDS);
                    assertThat(other).isEmpty();
                } catch (Exception exception) {
                    throw new AssertionError("SKIP LOCKED must not wait on an active transaction",exception);
                }
                transaction.setRollbackOnly();
            });
        } finally {
            jdbc.update("UPDATE trading_as400_sync_outbox SET status='BLOCKED',sync_state='FAILED',last_error='TEST_COMPLETE' WHERE order_id=?",orderId);
        }
    }

    @Test
    void sicouiSequenceIsBoundedCyclicAndNotUniqueByItself() {
        var sequence=jdbc.queryForMap("SELECT min_value,max_value,cycle FROM pg_sequences WHERE sequencename='trading_as400_sicoui_seq'");
        assertThat(sequence.get("min_value")).isEqualTo(1L);
        assertThat(sequence.get("max_value")).isEqualTo(999999L);
        assertThat(sequence.get("cycle")).isEqualTo(true);
        transactions.executeWithoutResult(transaction -> {
            long companyId=COMPANY_IDS.incrementAndGet();
            long accountId=createAccount(companyId);
            long first=createDraftOrder(accountId,companyId,"cycle-1-"+companyId);
            long second=createDraftOrder(accountId,companyId,"cycle-2-"+companyId);
            jdbc.update("INSERT INTO trading_as400_sync_outbox(order_id,target,sicoui) VALUES (?,'SICOUVI',1),(?,'SICOUVI',1)",first,second);
            assertThatThrownBy(()->jdbc.update("INSERT INTO trading_as400_sync_outbox(order_id,target,sicoui) VALUES (?,'SICOUVI',1000000)",first))
                    .isInstanceOf(DataAccessException.class);
            transaction.setRollbackOnly();
        });
    }

    private boolean reserveAfterBarrier(CountDownLatch ready, CountDownLatch start, long accountId, long orderId) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            reservations.reserve(accountId, Asset.EUR, new BigDecimal("80.000000"), orderId);
            return true;
        } catch (TradingException expected) {
            return false;
        }
    }

    private void verifyLocalDatabaseIsAvailable() {
        try (Connection ignored = TestConfig.dataSourceForLocalDatabase().getConnection()) {
            // La connexion suffit : Liquibase vérifie ensuite le schéma et les changements appliqués.
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "PostgreSQL locale indisponible sur localhost:5432 (base trading, utilisateur trading) : "
                            + exception.getMessage(),
                    exception);
        }
    }

    private long createAccount(long companyId) {
        Long id = jdbc.queryForObject("""
                INSERT INTO trading_account(company_id,base_currency,status)
                VALUES (?,'EUR','ACTIVE') RETURNING id
                """, Long.class, companyId);
        return id;
    }

    private long createDraftOrder(long accountId, long companyId, String key) {
        Long id = jdbc.queryForObject("""
                INSERT INTO trading_order(
                    account_id,company_id,asset,pair,side,order_type,requested_quantity,requested_unit,
                    quantity_oz,status,indicative_price,indicative_client_price_raw,indicative_client_price,
                    spread_applied,spread_config_version,idempotency_key,cl_ord_id)
                VALUES (?,?,'XAU','XAUEUR','BUY','SPOT',1,'OZ',1,'DRAFT',100,100,100,0.001,1,?,?)
                RETURNING id
                """, Long.class, accountId, companyId, key, "TEST-" + key);
        return id;
    }

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            return dataSourceForLocalDatabase();
        }

        private static DriverManagerDataSource dataSourceForLocalDatabase() {
            String password = System.getenv(DATABASE_PASSWORD_ENVIRONMENT_VARIABLE);
            if (password == null || password.isBlank()) {
                throw new IllegalStateException(
                        "La variable d'environnement TRADING_DB_PASSWORD est requise pour les tests PostgreSQL locaux");
            }
            return new DriverManagerDataSource(DATABASE_URL, DATABASE_USERNAME, password);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        SpringLiquibase liquibase(DataSource dataSource) {
            SpringLiquibase liquibase = new SpringLiquibase();
            liquibase.setDataSource(dataSource);
            liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");
            return liquibase;
        }

        @Bean
        TradingProperties tradingProperties() {
            TradingProperties properties = new TradingProperties();
            properties.getReservations().setTtl(Duration.ofMinutes(2));
            return properties;
        }

        @Bean
        BalanceRepository balanceRepository(JdbcTemplate jdbc) {
            return new BalanceRepository(jdbc);
        }

        @Bean
        ReservationRepository reservationRepository(JdbcTemplate jdbc) {
            return new ReservationRepository(jdbc);
        }

        @Bean
        ReservationService reservationService(BalanceRepository balances, ReservationRepository reservations, TradingProperties properties) {
            return new ReservationService(balances, reservations, properties);
        }

        @Bean
        LedgerRepository ledgerRepository(JdbcTemplate jdbc) {
            return new LedgerRepository(jdbc);
        }

        @Bean
        As400SyncOutboxRepository as400SyncOutboxRepository(JdbcTemplate jdbc) {
            return new As400SyncOutboxRepository(jdbc);
        }

        @Bean
        LedgerService ledgerService(BalanceRepository balances, LedgerRepository ledger, As400SyncOutboxRepository outbox) {
            return new LedgerService(balances, ledger, outbox);
        }

        @Bean
        FailingSettlement failingSettlement(LedgerService ledger) {
            return new FailingSettlement(ledger);
        }
    }

    static class FailingSettlement {
        private final LedgerService ledger;
        FailingSettlement(LedgerService ledger) { this.ledger = ledger; }

        @Transactional
        public void postThenFail(long accountId, long orderId) {
            ledger.postTrade(accountId, Asset.XAU, BigDecimal.ONE, Asset.EUR,
                    new BigDecimal("-100.000000"), orderId, "test:atomicity");
            throw new IllegalStateException("failure after ledger and outbox");
        }
    }
}
